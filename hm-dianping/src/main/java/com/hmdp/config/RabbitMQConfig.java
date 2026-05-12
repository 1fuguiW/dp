package com.hmdp.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ配置类
 *
 * 设计思路：
 * 1. 使用Direct交换机（直连模式）：消息根据routing key精确路由到指定队列
 * 2. 队列持久化：服务器重启后消息不丢失
 * 3. 单队列模式：秒杀订单统一进入一个队列，便于管理和监控
 *
 * 为什么选择Direct交换机而不是Fanout或Topic？
 * - Fanout：广播模式，会发送到所有绑定的队列，不适合我们的场景
 * - Topic：支持通配符路由，适合复杂的路由规则，我们的场景不需要
 * - Direct：精确匹配routing key，简单高效，适合秒杀场景
 */
@Configuration
public class RabbitMQConfig {

    /**
     * 秒杀订单队列名称
     * 命名规范：业务模块.功能.类型
     */
    public static final String SECKILL_QUEUE = "seckill.order.queue";

    /**
     * 秒杀订单交换机名称
     */
    public static final String SECKILL_EXCHANGE = "seckill.order.exchange";

    /**
     * 秒杀订单路由键
     * 生产者发送消息时使用此routing key，交换机根据此key将消息路由到队列
     */
    public static final String SECKILL_ROUTING_KEY = "seckill.order";

    /**
     * 创建秒杀订单队列
     *
     * @return Queue对象
     *
     * 参数说明：
     * - name: 队列名称
     * - durable: true表示持久化，RabbitMQ重启后队列依然存在
     * - exclusive: false表示非独占，多个消费者可以同时消费
     * - autoDelete: false表示不自动删除，即使没有消费者也保留队列
     */
    @Bean
    public Queue seckillQueue() {
        return new Queue(SECKILL_QUEUE, true);
    }

    /**
     * 创建秒杀订单交换机（Direct类型）
     *
     * @return DirectExchange对象
     *
     * Direct交换机特点：
     * - 消息的routing key必须与绑定的routing key完全匹配才会路由
     * - 性能高，路由规则简单
     */
    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange(SECKILL_EXCHANGE);
    }

    /**
     * 绑定队列和交换机
     *
     * @return Binding对象
     *
     * 绑定关系：
     * seckillQueue <-- SECKILL_ROUTING_KEY --> seckillExchange
     *
     * 工作流程：
     * 1. 生产者发送消息到交换机，指定routing key = "seckill.order"
     * 2. 交换机根据routing key找到绑定关系
     * 3. 将消息路由到seckillQueue队列
     * 4. 消费者从队列中取出消息处理
     */
    @Bean
    public Binding seckillBinding() {
        return BindingBuilder
                .bind(seckillQueue())           // 绑定队列
                .to(seckillExchange())          // 到交换机
                .with(SECKILL_ROUTING_KEY);     // 使用routing key
    }
}
