package com.hmdp.utils;

import com.sun.org.apache.xpath.internal.operations.Bool;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {


    private String name;
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString() + "-" ;

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标识
        String threadID = ID_PREFIX + Thread.currentThread().getId();

        //获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadID, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }


    @Override
    public void unLock() {
            //调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
                );
    }



//    @Override
//    public void unLock() {
//        //要验证一次这个锁是不是当初创建的那一个锁
//
//        //获取线程标识
//        String threadID = ID_PREFIX + Thread.currentThread().getId();
//
//        //获取锁
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//
//        if (threadID.equals(id)){
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
    // }

}
