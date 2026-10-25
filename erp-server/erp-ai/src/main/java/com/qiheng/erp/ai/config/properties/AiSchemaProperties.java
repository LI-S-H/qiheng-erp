package com.qiheng.erp.ai.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 授权 Schema 快照的缓存参数，对应 {@code erp.ai.schema.*}。
 *
 * @param cacheTtlSeconds 缓存有效期（秒）；0 表示立即过期，负数按 0 处理
 * @author Li
 * @since 2026-10-26
 */
@ConfigurationProperties(prefix = "erp.ai.schema")
public record AiSchemaProperties(@DefaultValue("300") long cacheTtlSeconds) {
}