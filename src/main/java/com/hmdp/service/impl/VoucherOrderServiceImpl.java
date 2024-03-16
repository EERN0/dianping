package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 优惠券订单服务实现类
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;   // 秒杀券service
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    // 优惠券秒杀，一人一单lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor(); // 单线程的线程池

    @PostConstruct  // 在这个类完成之后执行这个方法
    private void init() {
        // 异步线程，执行保存订单的任务
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 任务类
    private class VoucherOrderHandler implements Runnable {

        // 项目启动前，得在redis命令行输入，否则会死循环报错：xgroup create stream.orders g1 0 MKSTREAM
        String queueName = "stream.orders";  // 消息队列名字

        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息
                    // XREADGROUP GROUP g1 c1(消费者组g1里的消费者c1) COUNT 1(1条) BLOCK 2000(阻塞2s) STREAMS stream.order(消息队列名) > (>表示读队列未被消费的消息)
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())   // 获取消息队列中未消费的消息 new ReadOffset(">")
                    );
                    // 2. 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 如果获取失败，说明消息队列没有新消息，继续下一次循环
                        continue;
                    }
                    // 3. 解析消息中的订单消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4. 如果获取成功，可以下单，处理订单消息
                    handleVoucherOrder(voucherOrder);
                    // 5. ACK确认
                    // redis命令: SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    // 消息没有被确认(出现异常)，消息进入pending-list，要取出pending-list中的订单消息，继续处理并确认
                    handlePendingList();
                }
            }
        }

        // 处理异常的消息（未确认的订单信息），即pending-list里的，确保异常的订单一定能得到处理
        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取pending-list中的订单信息
                    // XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.order 0（0表示不是从消息队列中取出消息，而是从pending-list中取出消息）
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2. 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 获取失败，说明pending-list没有异常的订单信息，结束循环
                        break;
                    }
                    // 3. 解析消息中的订单消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4. 如果获取成功，可以下单，处理订单消息
                    handleVoucherOrder(voucherOrder);
                    // 5. ACK确认 （确认pending-list里的消息，至pending-list清空）
                    // SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list异常", e);
                    try {
                        Thread.sleep(20);   // 休眠下，避免太频繁
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }

    //// 阻塞队列，次新版本（最新版本在上面，不再使用阻塞队列，改用消息队列）
    //private final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    //
    //// 任务类
    //private class VoucherOrderHandler implements Runnable {
    //    @Override
    //    public void run() {
    //        while (true) {
    //            try {
    //                // 1.获取阻塞队列中的订单信息
    //                VoucherOrder order = orderTasks.take();
    //                // 2.创建订单
    //                handleVoucherOrder(order);
    //            } catch (InterruptedException e) {
    //                log.error("处理订单异常", e);
    //            }
    //        }
    //    }
    //}

    // 异步处理，不再需要返回值了
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1.获取用户id（由于是线程池里的线程，不能再去UserHolder里获取userId了，子线程不能获取父线程的ThreadLocal）
        Long userId = voucherOrder.getUserId();
        // 2.创建锁对象(一个用户一把锁)
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 3.获取锁
        boolean isLock = lock.tryLock();
        // 4.判断是否获取锁成功
        if (!isLock) {
            // 获取锁失败
            log.error("不允许重复下单");
        }
        try {
            // 拿到线程的代理对象，异步执行创建订单
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    // 当前对象的代理对象（为了让子线程能够拿到父线程的同一个代理对象）
    private IVoucherOrderService proxy;  // 获取当前对象的代理对象


    /**
     * [优惠券秒杀下单] 2.0最新版本：使用lua脚本+redis消息队列
     *
     * @param voucherId 优惠券id
     */
    public Result seckillVoucher(Long voucherId) {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 订单id
        long orderId = redisIdWorker.generateId("order");
        // 1.执行lua脚本，判断用户有无购买资格
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        // 2.判断结果是否为0
        int r = result.intValue();
        if (r != 0) {
            // 2.1 不为0，没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 下面这段注释的在新lua脚本里完成了

        //// 2.2 为0，用户有购买资格，把下单信息(优惠券订单信息)保存到阻塞队列
        //
        //VoucherOrder voucherOrder = new VoucherOrder();
        //// 2.3 生成订单id
        //voucherOrder.setId(orderId);    // 订单id，并非主键自增的，按照自定义规则生成
        //// 2.4 用户id
        //voucherOrder.setUserId(userId);
        //// 2.5 优惠券id
        //voucherOrder.setVoucherId(voucherId);
        //// 2.6 订单放入阻塞队列，交给异步线程处理
        //orderTasks.add(voucherOrder);

        // 3. 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 4. 返回订单id
        return Result.ok(orderId);
    }

    ///**
    // * [优惠券秒杀下单] 1.1版本：使用lua脚本+阻塞队列
    // *
    // * @param voucherId 优惠券id
    // */
    //public Result seckillVoucher(Long voucherId) {
    //    // 获取用户id
    //    Long userId = UserHolder.getUser().getId();
    //    // 1.执行lua脚本，判断用户有无购买资格
    //    Long result = stringRedisTemplate.execute(
    //            SECKILL_SCRIPT,
    //            Collections.emptyList(),
    //            voucherId.toString(), userId.toString()
    //    );
    //    // 2.判断结果是否为0
    //    int r = result.intValue();
    //    if (r != 0) {
    //        // 2.1 不为0，没有购买资格
    //        return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
    //    }
    //    // 2.2 为0，用户有购买资格，把下单信息(优惠券订单信息)保存到阻塞队列
    //    // 订单id
    //    long orderId = redisIdWorker.generateId("order");
    //    VoucherOrder voucherOrder = new VoucherOrder();
    //    // 2.3 生成订单id
    //    voucherOrder.setId(orderId);    // 订单id，并非主键自增的，按照自定义规则生成
    //    // 2.4 用户id
    //    voucherOrder.setUserId(userId);
    //    // 2.5 优惠券id
    //    voucherOrder.setVoucherId(voucherId);
    //    // 2.6 订单放入阻塞队列，交给异步线程处理
    //    orderTasks.add(voucherOrder);
    //
    //    // 3. 获取代理对象
    //    proxy = (IVoucherOrderService) AopContext.currentProxy();
    //
    //    // 4. 返回订单id
    //    return Result.ok(orderId);
    //}

    // [优惠券秒杀下单] 1.0版本：
    //@Override
    //public Result seckikllVoucher(Long voucherId) {
    //    // 1.查询优惠券
    //    SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
    //    // 2.判断秒杀是否开始
    //    if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
    //        return Result.fail("秒杀活动尚未开始");
    //    }
    //    // 3.判断秒杀是否结束
    //    if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
    //        return Result.fail("秒杀活动已结束");
    //    }
    //    // 4.判断库存是否充足
    //    if (voucher.getStock() < 1) {
    //        return Result.fail("库存不足!");
    //    }
    //
    //    // 先提交事务，再释放锁。避免事务没提交就释放锁了
    //    Long userId = UserHolder.getUser().getId();
    //    // 1-创建锁对象，一个用户id一把锁
    //    //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
    //    RLock lock = redissonClient.getLock("lock:order:" + userId);
    //    // 2-获取锁
    //    boolean isLock = lock.tryLock();
    //    // 3-判断是否获取锁成功
    //    if (!isLock) {
    //        // 获取锁失败
    //        return Result.fail("同一用户不允许重复下单!");
    //    }
    //    try {
    //        // 获取当前对象的代理对象（事务）。因为spring事务是通过代理对象执行的，而方法是当前类对象this，不是一个对象
    //        IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();  // 获取当前对象的代理对象
    //        return proxy.createVoucherOrder(voucherId);
    //    } finally {
    //        // 4-释放锁
    //        lock.unlock();
    //    }
    //}


    /**
     * 创建秒杀券订单，保存到数据库中
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5.一人一单
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 判断是否满足：一个用户只能买一张秒杀券
        if (count > 0) {
            // 用户已经买过这张秒杀券
            log.error("该秒杀券只能购买一次");
            return;
        }

        // 6.扣减库存（写tb_seckill_voucher表）
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")    // set stock = stock-1
                .eq("voucher_id", voucherId).gt("stock", 0)    // where id=? and stock>0
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存不足!");
            return;
        }

        //// 7.创建订单
        //VoucherOrder voucherOrder = new VoucherOrder();
        //// 7.1 生成订单id
        //long orderId = redisIdWorker.generateId("order");
        //voucherOrder.setId(orderId);    // 订单id，并非主键自增的，按照自定义规则生成
        //// 7.2 用户id
        //voucherOrder.setUserId(userId);
        //// 7.3 优惠券id
        //voucherOrder.setVoucherId(voucherId);

        // 7.写入订单数据（写tb_voucher_order表）
        save(voucherOrder);

        //// 8.返回订单id
        //return Result.ok(orderId);
    }
}
