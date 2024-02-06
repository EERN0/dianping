package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 店铺类型-服务实现类
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询店铺分类列表
     * case1: redis中value数据类型是String
     */
    //@Override
    //public Result queryShopTypeList() {
    //    // 1.查缓存中是否有店铺分类数据
    //    String key = "cache:type-list";
    //    String shopTypeListJson = stringRedisTemplate.opsForValue().get(key);
    //    // 2.缓存中有，返回缓存数据
    //    if (StrUtil.isNotBlank(shopTypeListJson)) {
    //        List<ShopType> list = JSONUtil.toList(shopTypeListJson, ShopType.class);
    //        return Result.ok(list);
    //    }
    //    // 3.缓存中没有，查询数据库
    //    List<ShopType> typeList = query().orderByAsc("sort").list();
    //    // 4.数据库没有，返回报错信息
    //    if (typeList == null || typeList.isEmpty()) {
    //        return Result.fail("店铺分类不存在");
    //    }
    //    // 5.写入redis缓存
    //    stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList)); // 序列化成json字符串存入redis
    //    // 6.返回
    //    return Result.ok(typeList);
    //}

    /**
     * 查询店铺分类列表
     * case2: redis中value数据类型是List
     */
    @Override
    public Result queryShopTypeList() {
        // 1.查缓存中是否有店铺分类数据
        String key = "cache:type-list";
        List<String> shopTypeListJson = stringRedisTemplate.opsForList().range(key, 0, -1);
        // 2.缓存中有，直接返回
        if (shopTypeListJson != null && !shopTypeListJson.isEmpty()) {
            List<ShopType> shopTypeList = shopTypeListJson.stream().map(s -> {
                ShopType shopType = JSONUtil.toBean(s, ShopType.class);
                return shopType;
            }).collect(Collectors.toList());

            return Result.ok(shopTypeList);
        }
        // 3.缓存中没有，查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        // 4.数据库没有，返回报错信息
        if (typeList == null || typeList.isEmpty()) {
            return Result.fail("店铺分类不存在");
        }
        // 5.写入redis缓存
        List<String> typeList2Json = typeList.stream().map(s -> {
            String json = JSONUtil.toJsonStr(s);
            return json;
        }).collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(key, typeList2Json); // rpush第一个放入的元素在最左边
        // 6.返回
        return Result.ok(typeList);
    }

}
