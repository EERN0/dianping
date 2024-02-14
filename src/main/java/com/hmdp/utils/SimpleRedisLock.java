package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * redis实现分布式锁 （key是锁名，value是线程标识）
 * 获取锁：set lock thread1 NX EX 10
 * 释放锁：del lock
 */
public class SimpleRedisLock implements ILock {

    private static final String KEY_PREFIX = "lock:";    // 锁前缀名
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-"; // 线程标识前缀 UUID-

    // static变量，在编译期间，读取lua脚本。而不是在调用对象时，加载脚本文件，减少io
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

    private String name;    // 锁名字
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 尝试获取锁
     *
     * @param timeoutSec 锁的超时时间，过期后自动释放
     * @return true-获取锁成功；false-获取锁失败
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标识: UUID-线程id
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);    // 自动拆箱Boolean-->boolean，避免null产生空指针异常
    }

    @Override
    public void unlock() {
        // 调用lua脚本
        // 参数: lua脚本、redis中锁的key，锁的value（当前线程标识，即线程id）
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }

    ///**
    // * 释放锁：
    // * 获取线程标识，判断是否与当前线程标识一致，一致才删掉锁
    // */
    //@Override
    //public void unlock() {
    //    // 获取线程标识
    //    String threadId = ID_PREFIX + Thread.currentThread().getId();
    //    // 获取锁中的标识（redis的value）
    //    String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
    //    // 判断标识是否一致
    //    if (threadId.equals(id)) {  // !!!这里是[查询判断]后再[释放锁]，无法保证原子性，改用lua脚本批量处理
    //        // 线程标识一致，释放锁
    //        stringRedisTemplate.delete(KEY_PREFIX + name);
    //    }
    //}
}
