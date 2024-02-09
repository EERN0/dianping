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
import lombok.Synchronized;
import org.springframework.aop.framework.AopContext;
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

        // 先提交事务，再释放锁。避免事务没提交就释放锁了
        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) { // intern()方法保证常量池对象唯一，而非new一个新的对象。当userId的值一样时，锁就一样
            // 获取当前对象的代理对象（事务）。因为spring事务是通过代理对象执行的，而方法是当前类对象this，不是一个对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();  // 获取当前对象的代理对象
            return proxy.createVoucherOrder(voucherId);
        }
    }

    /**
     * 订单创建
     *
     * @param voucherId 优惠券id
     */
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5.一人一单
        Long userId = UserHolder.getUser().getId();
        // 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 判断是否满足：一个用户只能买一张秒杀券
        if (count > 0) {
            // 该用户已经买过这张秒杀券
            return Result.fail("该秒杀券只能购买一次");
        }

        // 6.扣减库存（写tb_seckill_voucher表）
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")    // set stock = stock-1
                .eq("voucher_id", voucherId).gt("stock", 0)    // where id=? and stock>0
                .update();
        if (!success) {
            return Result.fail("库存不足!");
        }
        // 7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1 生成订单id
        long orderId = redisIdWorker.generateId("order");
        voucherOrder.setId(orderId);    // 订单id，并非主键自增的，按照自定义规则生成
        // 7.2 用户id
        voucherOrder.setUserId(userId);
        // 7.3 代金券id
        voucherOrder.setVoucherId(voucherId);

        // 7.写入订单数据（写tb_voucher_order表）
        save(voucherOrder);

        // 8.返回订单id
        return Result.ok(orderId);
    }
}
