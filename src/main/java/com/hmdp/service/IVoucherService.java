package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherService extends IService<Voucher> {

    /**
     * 查询店铺的优惠券列表
     *
     * @param shopId 店铺id
     */
    Result queryVoucherOfShop(Long shopId);

    /**
     * 新增秒杀券,同时将优惠券信息保存到redis中
     *
     * @param voucher 优惠券
     */
    void addSeckillVoucher(Voucher voucher);
}
