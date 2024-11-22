package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    // 设置初始时间
    private static final long BEGIN_STAMP=1068292980;
    private static final int COUNT_BITS=32;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String prefix) {
        // 设置时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond=now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp=nowSecond-BEGIN_STAMP;
        // 生成序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:" + prefix + ":" + date);
        // 低位进行或运算
        return timeStamp<<COUNT_BITS|count;
    }
}
