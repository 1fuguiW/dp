package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIDWorker {

    //开始时间戳
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    private static final long COUNT_BITS = 32;


    private StringRedisTemplate stringRedisTemplate;

    public RedisIDWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }


    public long nextId(String keyPrefix) {
        //生成时间戳
        LocalDateTime now = LocalDateTime.now();
        //变成秒
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        //生成序列号
            //日期精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
            //返回的是 redis里面自增的id  0 1 2 3
        long count  = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        //拼接  再跟前面的时间戳区拼接
        return timestamp << COUNT_BITS | count;


    }



}
