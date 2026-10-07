package com.qiheng.erp.purchase.mq;

import org.apache.rocketmq.spring.annotation.ExtRocketMQTemplateConfiguration;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 评分重算专用消息模板，业务服务按具体类型注入，避免误用其他生产者组。
 *
 * <p>NameServer、发送超时和重试次数沿用公共 RocketMQ 配置；
 * 生产者注册、启动和关闭由 Starter 管理，无需手动调用 start 或 shutdown。</p>
 */
@ExtRocketMQTemplateConfiguration(
        group = "${supplier-score.rocketmq.producer.group:supplier-score-producer}"
)
@ConditionalOnProperty(prefix = "rocketmq", name = "name-server")
public class SupplierScoreRocketMQTemplate extends RocketMQTemplate {
}
