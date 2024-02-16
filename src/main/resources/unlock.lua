-- 释放锁的lua脚本呢，保证原子性，用在SimpleRedisLock.java中

-- 比较线程标识与锁中的标识是否一致
if (redis.call('get', KEYS[1]) == ARGV[1]) then
    -- 释放锁 del key
    return redis.call('del', KEYS[1])
end
-- 不一致，返回0
return 0