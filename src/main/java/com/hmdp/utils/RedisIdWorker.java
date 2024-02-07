package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 生成的id格式：符号位(1bit)+时间戳(31bit--换算成秒)+序列号(32bit)
 */
@Component
public class RedisIdWorker {

    private static final long BEGIN_TIMESTAMP = 1704067200L;    // 开始时间戳
    private static final int COUNT_BIT = 32;    // 序列号的位数

    private final StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long generateId(String keyPrefix) {
        // 1.生成时间戳 = 当前时间戳 - 开始时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号
        // 2.1 获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2 自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);  // count占32bit

        // 3.拼接id，返回
        return timestamp << COUNT_BIT | count;
    }

    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2024, 1, 1, 0, 0, 0);
        long epochSecond = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println("second: " + epochSecond);   // second: 1704067200
    }
}
