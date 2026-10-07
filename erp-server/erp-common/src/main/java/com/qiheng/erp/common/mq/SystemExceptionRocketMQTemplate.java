package com.qiheng.erp.common.mq;

import org.apache.rocketmq.spring.annotation.ExtRocketMQTemplateConfiguration;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 系统异常记录专用消息模板，业务发送方按具体类型注入，避免误用其他生产者组。
 *
 * <p>NameServer、发送超时和重试次数沿用公共 RocketMQ 配置；
 * 生产者注册、启动和关闭由 Starter 管理，无需手动调用 start 或 shutdown。
 * 未配置 rocketmq.name-server 时不装配该 Bean，
 * {@link SystemExceptionMqPublisher} 通过 ObjectProvider 判空跳过发送。</p>
 *
 * @author Li
 * @since 2026-10-07
 */
@ExtRocketMQTemplateConfiguration(
        group = "${system-exception.rocketmq.producer.group:erp-system-exception-producer}"
)
@ConditionalOnProperty(prefix = "rocketmq", name = "name-server")
public class SystemExceptionRocketMQTemplate extends RocketMQTemplate {
}
