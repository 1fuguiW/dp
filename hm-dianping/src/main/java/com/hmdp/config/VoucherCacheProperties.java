package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "hmdp.cache.voucher-list")
public class VoucherCacheProperties {
    /**
     * 缓存模式: mysql(仅DB), redis(仅Redis+DB回源), caffeine(本地缓存+Redis+DB回源)
     */
    private String mode = "caffeine";

    /**
     * 本地缓存刷新时间, 默认 5 seconds
     */
    private Duration refreshAfterWrite = Duration.ofSeconds(5);

    /**
     * 本地缓存硬过期时间, 默认 10 minutes
     */
    private Duration expireAfterWrite = Duration.ofMinutes(10);

    /**
     * Redis 缓存过期时间, 默认 10 minutes
     */
    private Duration redisTtl = Duration.ofMinutes(10);
}
