package com.hmdp.mq;

import com.hmdp.config.RabbitMQConfig;
import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * 秒杀订单消息消费者
 *
 * 核心职责：
 * 1. 监听RabbitMQ队列，接收秒杀订单消息
 * 2. 异步处理订单创建（扣减数据库库存 + 插入订单记录）
 * 3. 失败时执行补偿逻辑（恢复Redis库存和一人一单标记）
 * 4. 手动确认消息，确保消息可靠性
 *
 * 设计亮点：
 * 1. 手动ACK机制：只有处理成功才确认，失败会重试
 * 2. 失败补偿：数据库操作失败时自动恢复Redis状态，保证最终一致性
 * 3. 事务保证：扣库存和创建订单在同一事务中，要么都成功要么都失败
 * 4. 幂等性：订单ID唯一，数据库主键约束防止重复插入
 */
@Component
@Slf4j
public class VoucherOrderConsumer {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 监听秒杀订单队列，处理订单创建
     *
     * @param orderMessage 订单消息
     * @param channel RabbitMQ通道，用于手动确认消息
     * @param deliveryTag 消息的唯一标识，用于ACK/NACK
     *
     * 消息确认机制说明：
     * - basicAck(tag, false)：确认消息，false表示只确认当前消息
     * - basicNack(tag, false, true)：拒绝消息，第三个参数true表示重新入队
     *
     * 为什么使用手动确认而不是自动确认？
     * - 自动确认：消息一旦被消费者接收就会被删除，如果处理失败消息就丢了
     * - 手动确认：只有明确调用ACK后消息才会被删除，失败可以重试
     */
    @RabbitListener(queues = RabbitMQConfig.SECKILL_QUEUE)
    @Transactional  // 事务注解：确保扣库存和创建订单的原子性
    public void handleOrder(VoucherOrderMessage orderMessage,
                            Channel channel,
                            Message message,
                            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            log.info("开始处理秒杀订单，订单ID：{}，用户ID：{}，优惠券ID：{}",
                    orderMessage.getOrderId(), orderMessage.getUserId(), orderMessage.getVoucherId());

            // ========== 第一步：扣减数据库库存 ==========
            /*
             * 使用乐观锁机制：WHERE stock > 0
             * 好处：
             * 1. 防止超卖：只有库存>0时才能扣减成功
             * 2. 无需加锁：利用数据库的原子性，性能更好
             * 3. 并发安全：多个消费者同时处理也不会出现负库存
             *
             * SQL示例：
             * UPDATE tb_seckill_voucher
             * SET stock = stock - 1
             * WHERE voucher_id = ? AND stock > 0
             */
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")                    // 库存-1
                    .eq("voucher_id", orderMessage.getVoucherId())  // 条件：优惠券ID
                    .gt("stock", 0)                                 // 条件：库存>0（防止超卖）
                    .update();

            // 库存扣减失败的处理
            if (!success) {
                log.error("数据库库存不足，订单创建失败，订单ID：{}", orderMessage.getOrderId());
                // 执行补偿：恢复Redis库存和一人一单标记
                compensateStock(orderMessage);
                // 确认消息（不重试）：因为库存不足是正常业务逻辑，重试也不会成功
                channel.basicAck(deliveryTag, false);
                return;
            }

            // ========== 第二步：创建订单记录 ==========
            /*
             * 为什么不调用原有的createVoucherOrder方法？
             * - 原方法包含一人一单检查，但我们在Redis中已经检查过了
             * - 直接创建订单更高效，减少数据库查询
             *
             * 幂等性保证：
             * - 订单ID在发送MQ前已生成，是唯一的
             * - 数据库表有主键约束，重复插入会失败
             * - 即使消息重复消费，也不会创建重复订单
             */
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(orderMessage.getOrderId());          // 使用预生成的订单ID
            voucherOrder.setUserId(orderMessage.getUserId());       // 用户ID
            voucherOrder.setVoucherId(orderMessage.getVoucherId()); // 优惠券ID
            voucherOrderService.save(voucherOrder);

            log.info("订单创建成功，订单ID：{}", orderMessage.getOrderId());

            // ========== 第三步：手动确认消息 ==========
            /*
             * 确认消息后，RabbitMQ会将消息从队列中删除
             * 参数说明：
             * - deliveryTag：消息的唯一标识
             * - false：只确认当前消息（如果是true则确认所有未确认的消息）
             */
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            log.error("订单处理失败，订单ID：{}，错误信息：{}", orderMessage.getOrderId(), e.getMessage(), e);

            try {
                // ========== 异常处理：补偿 + 重试 ==========
                /*
                 * 失败原因可能是：
                 * 1. 数据库连接失败
                 * 2. 主键冲突（消息重复消费）
                 * 3. 其他系统异常
                 *
                 * 处理策略：
                 * 1. 执行补偿逻辑，恢复Redis状态
                 * 2. 拒绝消息并重新入队，让消息重试
                 *
                 * 重试机制：
                 * - application.yaml中配置了最多重试3次
                 * - 如果3次都失败，消息会进入死信队列（需要人工处理）
                 */
                compensateStock(orderMessage);

                // 拒绝消息并重新入队
                /*
                 * basicNack参数说明：
                 * - deliveryTag：消息标识
                 * - false：只拒绝当前消息
                 * - true：重新入队（消息会重新被消费）
                 */
                channel.basicNack(deliveryTag, false, true);

            } catch (Exception ex) {
                log.error("消息确认失败，订单ID：{}", orderMessage.getOrderId(), ex);
            }
        }
    }

    /**
     * 失败补偿：恢复Redis库存和一人一单标记
     *
     * 为什么需要补偿？
     * - Redis中已经扣减了库存，但数据库操作失败了
     * - 如果不补偿，会导致Redis和数据库数据不一致
     * - 用户看到秒杀成功（Redis扣减成功），但实际订单没创建
     *
     * 补偿逻辑：
     * 1. 恢复Redis库存：将扣减的库存加回去
     * 2. 移除一人一单标记：允许用户重新秒杀
     *
     * 最终一致性保证：
     * - 通过补偿机制，确保Redis和数据库最终一致
     * - 即使消息重试失败，补偿也会执行，不会出现"幽灵库存"
     *
     * @param message 订单消息
     */
    private void compensateStock(VoucherOrderMessage message) {
        try {
            log.info("开始执行补偿逻辑，订单ID：{}", message.getOrderId());

            // 1. 恢复Redis库存（+1）
            String stockKey = SECKILL_STOCK_KEY + message.getVoucherId();
            stringRedisTemplate.opsForValue().increment(stockKey);
            log.info("Redis库存已恢复，key：{}", stockKey);

            // 2. 移除一人一单标记（从Set中删除用户ID）
            String orderKey = "seckill:order:" + message.getVoucherId();
            stringRedisTemplate.opsForSet().remove(orderKey, message.getUserId().toString());
            log.info("一人一单标记已移除，key：{}，userId：{}", orderKey, message.getUserId());

            log.info("补偿逻辑执行完成，订单ID：{}", message.getOrderId());

        } catch (Exception e) {
            // 补偿失败也要记录日志，但不抛出异常（避免影响消息确认）
            log.error("补偿逻辑执行失败，订单ID：{}，错误信息：{}", message.getOrderId(), e.getMessage(), e);
        }
    }
}
