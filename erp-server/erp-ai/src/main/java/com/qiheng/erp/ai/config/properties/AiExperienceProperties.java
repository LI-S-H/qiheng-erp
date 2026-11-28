package com.qiheng.erp.ai.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 经验提取与注入配置，对应 {@code erp.ai.experience.*}。
 *
 * <p>提取每天一次，把已完成任务的经验写入经验表；合并随后整理；
 * 每轮对话只注入目录摘要，细节由检索工具按需查询。</p>
 *
 * @param enabled                 是否启用经验提取与注入
 * @param cron                    每日提取任务的 cron
 * @param maxTasksPerDay          兼容旧配置；状态队列不再使用此限制
 * @param lookbackDays            兼容旧配置；不再按流水回看天数筛选
 * @param minEvents               兼容旧配置；改按流水完整性筛选
 * @param extractionInputMaxTokens 提取输入预算
 * @param extractionMaxOutputTokens 提取输出上限
 * @param maxExperiencesPerTask   兼容旧配置；提示词仍建议每次提取 0~5 条
 * @param maxTextChars            单条经验最大字符数，超过丢弃
 * @param mergeMaxExperiences     兼容旧配置；合并不再截断有效经验
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
        @DefaultValue("flash") String model,
        @DefaultValue("80000") int mergeInputMaxTokens,
        @DefaultValue("35000") int mergeMaxOutputTokens,
        @DefaultValue("100") int mergePageSize,
        @DefaultValue("2") int extractionWorkerThreads,
        @DefaultValue("1000") long transientRetryBackoffMillis,
        @DefaultValue("3") int transientMaxAttemptsPerRun,
        @DefaultValue("10") int extractionMaxAccepted) {

    @ConstructorBinding
    public AiExperienceProperties {
    }

    /** 保留既有调用方的构造方式；新增预算与退避参数使用默认值。 */
    public AiExperienceProperties(boolean enabled, String cron, int maxTasksPerDay, int lookbackDays,
                                  int minEvents, int extractionInputMaxTokens, int extractionMaxOutputTokens,
                                  int maxExperiencesPerTask, int maxTextChars, int mergeMaxExperiences,
                                  int summaryMaxChars, int searchLimit, long cacheSeconds, String model) {
        this(enabled, cron, maxTasksPerDay, lookbackDays, minEvents, extractionInputMaxTokens,
                extractionMaxOutputTokens, maxExperiencesPerTask, maxTextChars, mergeMaxExperiences,
                summaryMaxChars, searchLimit, cacheSeconds, model, 80000, 35000, 100, 2, 1000, 3, 10);
    }

    public AiExperienceProperties(boolean enabled, String cron, int maxTasksPerDay, int lookbackDays,
                                  int minEvents, int extractionInputMaxTokens, int extractionMaxOutputTokens,
                                  int maxExperiencesPerTask, int maxTextChars, int mergeMaxExperiences,
                                  int summaryMaxChars, int searchLimit, long cacheSeconds, String model,
                                  int mergeInputMaxTokens, int mergeMaxOutputTokens, int mergePageSize,
                                  int extractionWorkerThreads, long transientRetryBackoffMillis,
                                  int transientMaxAttemptsPerRun) {
        this(enabled, cron, maxTasksPerDay, lookbackDays, minEvents, extractionInputMaxTokens,
                extractionMaxOutputTokens, maxExperiencesPerTask, maxTextChars, mergeMaxExperiences,
                summaryMaxChars, searchLimit, cacheSeconds, model, mergeInputMaxTokens, mergeMaxOutputTokens,
                mergePageSize, extractionWorkerThreads, transientRetryBackoffMillis, transientMaxAttemptsPerRun, 10);
    }
}
