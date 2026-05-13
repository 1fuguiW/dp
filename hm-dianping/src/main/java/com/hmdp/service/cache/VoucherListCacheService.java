package com.hmdp.service.cache;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.hmdp.config.VoucherCacheProperties;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_VOUCHER_LIST_KEY;

/**
 * 优惠券列表多级缓存服务
 *
 * 三级缓存架构：
 * L1: Caffeine 本地缓存（进程内，微秒级）
 * L2: Redis 分布式缓存（毫秒级）
 * L3: MySQL 数据库（十毫秒级）
 *
 * 读取顺序：Caffeine → Redis → MySQL，逐级回源并回写上层缓存
 */
@Slf4j
@Service
public class VoucherListCacheService {

    @Resource
    private VoucherMapper voucherMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private VoucherCacheProperties cacheProperties;

    private LoadingCache<Long, List<Voucher>> voucherListCache;

    @PostConstruct
    public void initCache() {
        voucherListCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .refreshAfterWrite(cacheProperties.getRefreshAfterWrite().toMillis(), TimeUnit.MILLISECONDS)
                .expireAfterWrite(cacheProperties.getExpireAfterWrite().toMillis(), TimeUnit.MILLISECONDS)
                .build(new CacheLoader<Long, List<Voucher>>() {
                    @Override
                    public List<Voucher> load(Long shopId) {
                        return loadFromRedisThenDb(shopId);
                    }
                });
    }

    /**
     * 根据商户ID获取优惠券列表（多级缓存）
     */
    public List<Voucher> getVoucherByShopId(Long shopId) {
        String mode = cacheProperties.getMode();
        switch (mode.toLowerCase(Locale.ROOT)) {
            case "mysql":
                log.debug("【缓存模式: mysql】直接查询数据库, shopId={}", shopId);
                return loadFromDb(shopId);
            case "redis":
                log.debug("【缓存模式: redis】查询Redis+DB, shopId={}", shopId);
                return loadFromRedisThenDb(shopId);
            case "caffeine":
            default:
                log.debug("【缓存模式: caffeine】查询本地缓存, shopId={}", shopId);
                return voucherListCache.get(shopId);
        }
    }

    /**
     * L2回源逻辑：Redis → MySQL
     */
    private List<Voucher> loadFromRedisThenDb(Long shopId) {
        String key = CACHE_VOUCHER_LIST_KEY + shopId;

        // 1) 读Redis
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            log.debug("命中Redis缓存, shopId={}", shopId);
            return JSONUtil.toList(json, Voucher.class);
        }

        // 2) Redis未命中，读MySQL
        log.debug("Redis未命中, 查询数据库, shopId={}", shopId);
        List<Voucher> vouchers = loadFromDb(shopId);

        if (CollectionUtil.isNotEmpty(vouchers)) {
            // 3) 回写Redis
            long ttlMillis = cacheProperties.getRedisTtl().toMillis();
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(vouchers), ttlMillis, TimeUnit.MILLISECONDS);
            log.debug("数据已回写Redis, shopId={}, ttl={}ms", shopId, ttlMillis);
        }

        return vouchers;
    }

    private List<Voucher> loadFromDb(Long shopId) {
        List<Voucher> vouchers = voucherMapper.queryVoucherOfShop(shopId);
        return vouchers == null ? Collections.emptyList() : vouchers;
    }
}
