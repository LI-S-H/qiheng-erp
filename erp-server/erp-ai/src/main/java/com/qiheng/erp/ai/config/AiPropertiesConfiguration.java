package com.qiheng.erp.ai.config;

import com.qiheng.erp.ai.config.properties.AiGraphProperties;
import com.qiheng.erp.ai.config.properties.AiMemoryProperties;
import com.qiheng.erp.ai.config.properties.AiSqlProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 配置属性类的统一注册入口。
 *
 * <p><b>刻意不带 {@code @Conditional}</b>：属性 Bean 必须无条件存在。
 * 如果把它挂在 {@link DeepSeekChatModelConfig} 这类"模型可用才装配"的类上，
 * 没配 API Key 的环境里属性 Bean 就不会创建，而取数执行器只看
 * {@link AiSqlEnabledCondition}，启动时直接找不到依赖。</p>
 *
 * <p>属性 Bean 本身只是一份不可变的值对象，没有连接、没有线程，
 * 即使 AI 关闭也不会有运行成本。</p>
 *
 * @author Li
 * @since 2026-10-26
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        AiSqlProperties.class,
        AiGraphProperties.class,
        AiMemoryProperties.class
})
public class AiPropertiesConfiguration {
}