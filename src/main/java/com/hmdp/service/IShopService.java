package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 服务类
 */
public interface IShopService extends IService<Shop> {

    /**
     * 根据id查询商铺信息
     *
     * @param id
     * @return
     */
    Result queryById(Long id);
}
