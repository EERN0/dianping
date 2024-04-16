package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * 服务实现类
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheClient cacheClient;    // 导入redis工具类

    /**
     * 根据id查询店铺信息
     *
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        // 缓存穿透
        // Shop shop = queryWithCachePenetrate(id);
        Shop shop = cacheClient.queryWithCachePenetrate(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, id2 -> getById(id2), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 缓存击穿-互斥锁解决方案
        // Shop shop = queryWithCacheBreakdownWithMutex(id);

        // 缓存击穿-逻辑过期时间解决方案（得先预热key）
        //Shop shop = queryWithCacheBreakdownWithLogicalExpire(id);

        // 这个得先预热key
        //Shop shop = cacheClient.queryWithCacheBreakdownWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    // 重建缓存的线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 根据id查询店铺信息（缓存击穿--逻辑过期时间解决方案）
     */
    public Shop queryWithCacheBreakdownWithLogicalExpire(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 1.redis查缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.缓存是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 3.缓存没查到，返回null（不是热点key，没事先预热）
            return null;
        }
        // 4.缓存命中，需要把json-str反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        // TODO 尝试下强转为Shop: Shop shop = (Shop) redisData.getData();
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);  // 店铺数据
        LocalDateTime expireTime = redisData.getExpireTime();   // 逻辑过期时间
        // 5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1 未过期，直接返回店铺数据
            return shop;
        }
        // 5.2 过期，需要重建缓存

        // 6.缓存重建
        // 6.1 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean getLock = tryLock(lockKey);
        // 6.2 判断是否获取锁成功
        if (getLock) {  // 6.4 获取锁成功
            // 应该再次检测redis缓存是否过期，没过期直接返回 !! double check
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                // 6.3 重建缓存前，再次检测缓存是否过期
                redisData = JSONUtil.toBean(shopJson, RedisData.class);
                expireTime = redisData.getExpireTime();
                if (expireTime.isAfter(LocalDateTime.now())) {
                    // double check-->缓存没过期，说明前面拿到锁的线程已经重建缓存了
                    return JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
                }
            }
            // double check-->缓存过期，开辟线程重建缓存，原线程返回旧数据
            CACHE_REBUILD_EXECUTOR.execute(() -> {
                try {
                    // 重建缓存
                    this.saveShopData2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);    // 释放锁
                }
            });
        }
        // 6.4 返回过期的商品信息
        return shop;
    }

    /**
     * 根据id查询店铺信息（缓存击穿--互斥锁解决方案）
     */
    public Shop queryWithCacheBreakdownWithMutex(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 1.从redis查询店铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存中是否有数据
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.查到的数据非空字符串、非null，返回缓存数据
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 判断缓存命中的是否为null (shopJson要么是空字符串""，要么为null)
        if (shopJson != null) {
            return null;    // 空字符串，返回错误信息
        }
        // 4.实现缓存重建（shopJson为null，缓存无数据）
        // 4.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean getLock = tryLock(lockKey);
            // 4.2 判断是否获取成功
            if (!getLock) {
                // 4.3 获取锁失败，休眠并重试
                Thread.sleep(50);
                // 必须在递归调用后立即返回调用的结果！否则线程查到缓存后，还会执行下面的查询数据库操作
                return queryWithCacheBreakdownWithMutex(id);
            }
            // 4.4 获取锁成功，根据id查询数据库
            shop = getById(id);
            Thread.sleep(200);  // 模拟查询数据库耗时
            // 5. 数据库中不存在，将null值写入redis（防止缓存穿透）
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            }
            // 6. 数据库中存在，写入redis，设置过期时间
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7. 释放锁
            unlock(lockKey);
        }
        // 8. 返回
        return shop;
    }

    /**
     * 根据id查询店铺信息（缓存穿透解决方案）
     */
    public Shop queryWithCachePenetrate(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 1.从redis查询店铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断缓存中是否有数据
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.查到的数据非空字符串、非null，返回缓存数据
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 判断缓存命中的是否为null (shopJson要么是空字符串""，要么为null)
        if (shopJson != null) {
            // 空值，返回错误信息
            return null;
        }
        // 4.缓存无，根据id查询数据库
        Shop shop = getById(id);
        // 5.数据库不存在，将null写入redis（防止缓存穿透）
        if (shop == null) {
            // 空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }
        // 6.存在，写入redis，设置过期时间
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 7.返回
        return shop;
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


    /**
     * 缓存预热，添加热点key到redis中，设置逻辑过期时间
     */
    public void saveShopData2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 1.根据id查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);  // 模拟查询数据库耗时
        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3.写入redis (设置了逻辑过期时间，不需要添加ttl（默认永久有效，自行预热和清理热点key）)
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 更新店铺信息
     *
     * @param shop
     * @return
     */
    @Override
    @Transactional  // 更新数据库和删除缓存，构成事务
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }

        // 1.更新数据库
        updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());

        // 再开一个线程，延迟双删缓存（新线程不会受到主线程事务的影响）
        Thread deleteCacheThread = new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            // 延迟双删，再删一遍缓存
            stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        });
        deleteCacheThread.start();

        return Result.ok();
    }
}
