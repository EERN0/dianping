package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 店铺类型-服务类
 */
public interface IShopTypeService extends IService<ShopType> {

    /**
     * 查询店铺分类列表
     */
    Result queryShopTypeList();
}
