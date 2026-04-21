package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;


    //商铺查询 通过ID
    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //用互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);

        //解决缓存穿透
        Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop==null){
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

    //互斥锁方法查询
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;

        // 从redis查询商铺缓存
        String shopJson  = stringRedisTemplate.opsForValue().get(key);

        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            //存在，返回
            return shop;
        }


        //不存在，尝试获取互斥锁
        String lockKey ="lock:shop:" + id;

        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);

            //判断是否获取锁
            if (!isLock){
                //失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            //成功获取了锁 就查询数据库
            shop = getById(id);

            //数据不存在返回错误
            if (shop==null){
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }

            //存在，写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);


        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放锁
            unlock(lockKey);
        }

        return shop;
    }



    //防缓存穿透
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;

        // 从redis查询商铺缓存
        String shopJson  = stringRedisTemplate.opsForValue().get(key);

        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            //存在，返回
            return shop;
        }

        //不存在，根据id查询数据库
        Shop shop = getById(id);

        //不存在返回错误
        if (shop==null){
            return null;
        }

        //存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }






    //更新商铺信息
    @Override
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if (id==null){
            return Result.fail("店铺ID不能为空");
        }

        //先操作数据库
        updateById(shop);

        //再删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }



    //互斥锁
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
