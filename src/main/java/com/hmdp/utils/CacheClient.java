package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);    // 重建缓存的线程池

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 1、将任意对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 2、将任意对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿
     * 设置逻辑过期时间，不用设置key的TTL，手动预热缓存，清理缓存
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 3、根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值 (空字符串""，不是null) 解决缓存穿透问题
     */
    public <R, ID> R queryWithCachePenetrate(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 缓存命中
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // json为null 或者 空值""
        if (json != null) {
            // 缓存查到的是空值""。返回错误信息
            return null;
        }
        // 缓存未命中，根据id查数据库
        R r = dbFallback.apply(id);

        if (r != null) {    // 数据库中有，写入redis
            this.set(key, r, time, unit);
        } else {    // 数据库也没有，将空值""写入redis
            stringRedisTemplate.opsForValue().set(key, "", time, unit);
        }
        return r;
    }

    /**
     * 4、根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
     */
    public <R, ID> R queryWithCacheBreakdownWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 查缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 缓存未命中
        if (StrUtil.isBlank(json)) {
            return null;    // 不是热点key，没事先预热，返回null即可
        }
        // 缓存命中，解析json
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject jsonObject = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(jsonObject, type);      // 缓存中的java对象数据
        LocalDateTime expireTime = redisData.getExpireTime();   // 过期时间

        // 缓存没过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        // 缓存过期，需要重建缓存
        // 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean getLock = tryLock(lockKey);
        if (getLock) {   // 成功获取锁
            // 重建缓存前，需要再次检查缓存的过期时间，double check
            json = stringRedisTemplate.opsForValue().get(key);
            redisData = JSONUtil.toBean(json, RedisData.class);
            expireTime = redisData.getExpireTime();
            if (expireTime.isAfter(LocalDateTime.now())) {  // 现在的缓存又没过期
                return JSONUtil.toBean((JSONObject) redisData.getData(), type);    // 缓存中的新数据
            }
            // 还是过期的数据，要重建缓存了
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查数据库
                    R r1 = dbFallback.apply(id);
                    // 写入redis，带逻辑过期时间
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        // 返回旧数据（保证可用性）
        return r;
    }

    // 使用redis的setnx命令实现互斥锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // flag如果为null，自动拆箱时，由于没有对应的类型，回报空指针异常，所以使用BooleanUtil.isTrue()方法
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
