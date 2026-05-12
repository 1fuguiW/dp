package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 秒杀订单消息实体类
 *
 * 设计思路：
 * 1. 实现Serializable接口：RabbitMQ需要序列化消息进行网络传输和持久化
 * 2. 只包含必要字段：订单ID、用户ID、优惠券ID，减少消息体积
 * 3. 订单ID在发送MQ前生成：确保幂等性，即使消息重复消费也不会创建重复订单
 *
 * 为什么不直接传VoucherOrder对象？
 * - VoucherOrder包含很多字段（payType、status、createTime等），这些字段在消费时才需要设置
 * - 减少消息体积，提高传输效率
 * - 职责分离：Message只负责传递核心信息，业务逻辑在消费者中处理
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoucherOrderMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单ID
     *
     * 关键设计：在发送MQ前就生成订单ID
     * 好处：
     * 1. 幂等性保证：即使消息重复消费，订单ID相同，数据库主键冲突会阻止重复插入
     * 2. 用户体验：可以立即返回订单ID给用户，用户可以用此ID查询订单状态
     * 3. 链路追踪：整个异步流程可以通过订单ID串联起来
     */
    private Long orderId;

    /**
     * 用户ID
     *
     * 用于：
     * 1. 创建订单时设置userId字段
     * 2. 失败补偿时移除Redis中的一人一单标记
     */
    private Long userId;

    /**
     * 优惠券ID
     *
     * 用于：
     * 1. 扣减数据库库存（更新tb_seckill_voucher表）
     * 2. 创建订单时设置voucherId字段
     * 3. 失败补偿时恢复Redis库存
     */
    private Long voucherId;
}
