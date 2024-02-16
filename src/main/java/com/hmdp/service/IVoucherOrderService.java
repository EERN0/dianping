package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;


/**
 * 优惠券订单-service类
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 优惠券秒杀下单
     *
     * @param voucherId 优惠券id
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 创建秒杀券订单
     *
     * @param voucherOrder 优惠券订单
     */
    void createVoucherOrder(VoucherOrder voucherOrder);
}
