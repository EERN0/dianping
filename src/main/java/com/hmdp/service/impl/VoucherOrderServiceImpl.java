package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * 优惠券订单服务实现类
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;   // 秒杀券service

    @Resource
    private RedisIdWorker redisIdWorker;

    /**
     * 优惠券秒杀下单
     *
     * @param voucherId 优惠券id
     */
    @Override
    @Transactional
    public Result seckikllVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀活动尚未开始");
        }
        // 3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀活动已结束");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足!");
        }
        // 5.扣减库存（操作tb_seckill_voucher表）
        boolean success = seckillVoucherService.update().setSql("stock=stock-1").eq("voucher_id", voucherId).update();
        if (!success) {
            return Result.fail("库存不足!");
        }
        // 6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 6.1 生成订单id
        long orderId = redisIdWorker.generateId("order");
        voucherOrder.setId(orderId);    // 订单id，并非主键自增的，按照自定义规则生成
        // 6.2 用户id
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        // 6.3 代金券id
        voucherOrder.setVoucherId(voucherId);

        // 7.写入订单数据（操作tb_voucher_order表）
        save(voucherOrder);

        // 8.返回订单id
        return Result.ok(orderId);
    }
}
