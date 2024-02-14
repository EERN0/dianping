package com.hmdp.utils;

/**
 * redis实现分布式锁
 * 获取锁：set lock thread1 NX EX 10
 * 释放锁：del lock
 */
public interface ILock {

    /**
     * 尝试获取锁
     *
     * @param timeoutSec 锁的超时时间，过期后自动释放
     * @return true-获取锁成功；false-获取锁失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();

}
