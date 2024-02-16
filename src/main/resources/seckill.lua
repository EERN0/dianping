-- 秒杀券，一人一单lua脚本，判断用户有无购买资格
-- redis中的value数据结构为set（用在VoucherOrderServiceImpl.java中）

-- 1.参数列表
-- 1.1 秒杀券id
local voucherId = ARGV[1]
-- 1.2 用户id
local userId = ARGV[2]
-- 1.3 订单id
local orderId = ARGV[3]

-- 2.秒杀券在redis中的key
-- 2.1 库存key (value是秒杀券的库存)
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2 订单key (value是userId，表明该用户已下单)
local orderKey = 'seckill:order:' .. voucherId

-- 3.脚本业务
-- 3.1 判断库存是否充足 get stockKey
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 3.2 库存不足，返回1
    return 1
end
-- 3.2 判断用户是否下单(查redis中orderKey对应的value里是否有userId) SISMEMBER orderKey userId
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 3.3 存在，说明是用户已经抢到过一次秒杀券，重复下单，返回2
    return 2
end
-- 3.4 用户秒杀成功，扣减库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)
-- 3.5 用户下单，保存用户 --> 将userId存入到订单集合中 sadd orderKey userId
redis.call('sadd', orderKey, userId)
-- 3.6 发送消息到队列中 XADD stream.order * k1 v1 k2 v2 ...
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)

-- 4.成功，返回0
return 0