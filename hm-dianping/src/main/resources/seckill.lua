-- 优惠券ID
local voucherId = ARGV[1]

-- 用户ID
local userId = ARGV[2]

-- 库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 订单key
local orderKey = 'seckill:order:' .. voucherId
-- 两个点来拼接


--判断库存是否充足
if (tonumber(redis.call('get',stockKey)) <= 0)  then
    return 1
    --库存不足返回1
end

-- 判断用户是否重复下单
if (redis.call('sismember',orderKey,userId) == 1) then
    return 2
    --用户重复下单返回2
end


-- 扣减库存
redis.call('incrby',stockKey,-1)
-- 下单(保存用户)
redis.call('sadd',orderKey,userId)

-- 返回0表示成功
return 0