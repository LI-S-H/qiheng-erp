package com.qiheng.erp.ai.config;

import com.qiheng.erp.ai.config.properties.AiSchemaProperties;
import com.qiheng.erp.ai.sql.schema.SchemaCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 取数数据层的装配。
 *
 * <p>{@link SchemaCache} 原来用 {@code @Component} + {@code @Value} 自己取配置，
 * 改成配置类建 Bean 之后，service 类不依赖 Spring 注解，测试也能直接
 * {@code new SchemaCache(300)} 构造。</p>
 *
 * <p>本类不带条件：Schema 快照缓存与模型是否可用无关，AI 关闭时
 * {@link com.qiheng.erp.ai.sql.registry.SchemaSyncRunner} 仍然要用它。</p>
 *
 * @author Li
 * @since 2026-10-26
 */
@Configuration(proxyBeanMethods = false)
public class AiSchemaConfiguration {

    @Bean
    public SchemaCache schemaCache(AiSchemaProperties properties) {
        return new SchemaCache(properties);
    }
}