package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private final RedisIDWorker redisIDWorker;

    @Resource
    private SeckillVoucherServiceImpl seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    public VoucherOrderServiceImpl(RedisIDWorker redisIDWorker) {
        this.redisIDWorker = redisIDWorker;
    }


    //秒杀券抢购
    @Override
    public Result seckillVoucher(Long voucherId) {
        //查询优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束");
        }
        //判断库存
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();

        //创建锁对象
//        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);

        RLock lock = redissonClient.getLock("order:" + userId);

        //获取锁
//        boolean isLock = simpleRedisLock.tryLock(1200);
        boolean isLock = lock.tryLock();

        if (!isLock){
            return Result.fail("不允许重新下单");
        }


        try {
            // 获取代理对象 ?
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
        finally {
            lock.unlock();
        }


    }

    @Transactional
    public Result createVoucherOrder(Long voucherId){

        //一人一单
        Long userId = UserHolder.getUser().getId();

        //select count(*) from voucher_order where user_id = ? and voucher_id = ?
        int count = query().eq("user_id",userId).eq("voucher_id",voucherId).count();

        if (count > 0){
            return Result.fail("用户已经购买过一次");
        }

        //扣库存
        boolean success = seckillVoucherService.update().    // update seckill_voucher set stock = stock - 1 where id = ? and stock > 0
                setSql("stock = stock - 1")     //set stock = stock - 1
                .eq("voucher_id",voucherId).gt("stock",0)//where id = ? and stock > 0
                .update();

        if (!success){
            return Result.fail("库存不足");
        }


        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        long orderId = redisIDWorker.nextId("order");
        voucherOrder.setId(orderId);
        //用户ID
//      Long userId = UserHolder.getUser().getId();     //在线程里拿
        voucherOrder.setUserId(userId);
        //代金券ID
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        return Result.ok(orderId);
    }
}
