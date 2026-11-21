package com.qiheng.erp.ai.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 技能目录与刷新配置，对应 {@code erp.ai.skills.*}。
 *
 * <p>技能只从外部目录读取，不打包进 jar：调整技能内容后无需重新构建，
 * 定时检查文件变化并重新加载。</p>
 *
 * @param dir       技能根目录，其下按 shared、main、analysis 分组
 * @param refreshMs 文件变化检查间隔，单位毫秒
 * @author Li
 * @since 2026-11-22
 */
@ConfigurationProperties(prefix = "erp.ai.skills")
public record AiSkillProperties(
        @DefaultValue("./skills") String dir,
        @DefaultValue("30000") long refreshMs) {
}