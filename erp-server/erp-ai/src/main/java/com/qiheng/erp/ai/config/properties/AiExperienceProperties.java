package com.qiheng.erp.ai.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 经验提取与注入配置，对应 {@code erp.ai.experience.*}。
 *
 * <p>提取每天一次，把已完成任务的经验写入经验表；合并随后整理；
 * 每轮对话只注入目录摘要，细节由检索工具按需查询。</p>
 *
 * @param enabled                 是否启用经验提取与注入
 * @param cron                    每日提取任务的 cron
 * @param maxTasksPerDay          单次最多处理的任务数
 * @param lookbackDays            回看天数
 * @param minEvents               任务流水最少条数
 * @param extractionInputMaxTokens 提取输入预算
 * @param extractionMaxOutputTokens 提取输出上限
 * @param maxExperiencesPerTask   单个任务最多提取条数
 * @param maxTextChars            单条经验最大字符数，超过丢弃
 * @param mergeMaxExperiences     合并时最多读取的现有经验条数
 * @param summaryMaxChars         目录摘要最大字符数
 * @param searchLimit             检索最多返回条数
 * @param cacheSeconds            目录摘要缓存秒数
 * @param model                   提取使用的模型：flash 或 pro
 * @author Li
 * @since 2026-11-20
 */
@ConfigurationProperties(prefix = "erp.ai.experience")
public record AiExperienceProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("0 10 3 * * ?") String cron,
        @DefaultValue("100") int maxTasksPerDay,
        @DefaultValue("7") int lookbackDays,
        @DefaultValue("6") int minEvents,
        @DefaultValue("600000") int extractionInputMaxTokens,
        @DefaultValue("4000") int extractionMaxOutputTokens,
        @DefaultValue("5") int maxExperiencesPerTask,
        @DefaultValue("400") int maxTextChars,
        @DefaultValue("100") int mergeMaxExperiences,
        @DefaultValue("400") int summaryMaxChars,
        @DefaultValue("10") int searchLimit,
        @DefaultValue("60") long cacheSeconds,
        @DefaultValue("flash") String model) {
}