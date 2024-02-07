package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

/**
 * 服务实现类
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

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

        // 缓存击穿-互斥锁解决方案
        Shop shop = queryWithCacheBreakdownWithMutex(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 根据id查询店铺信息（缓存击穿--互斥锁解决方案）
     *
     * @param id
     * @return
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
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
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
     *
     * @param id
     * @return
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

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // flag如果为null，自动拆箱时，由于没有对应的类型，回报空指针异常，所以使用BooleanUtil.isTrue()方法
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
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

        return Result.ok();
    }
}
