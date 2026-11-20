package com.qiheng.erp.ai.job;

import com.qiheng.erp.ai.config.AiModelEnabledCondition;
import com.qiheng.erp.ai.config.properties.AiExperienceProperties;
import com.qiheng.erp.ai.experience.ExperienceExtractionService;
import com.qiheng.erp.ai.experience.ExperienceMergeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每天凌晨提取经验并合并。
 *
 * <p>提取和合并分开执行：提取失败不影响合并，合并失败保留已提取的条目。
 * 两个阶段都由配置开关控制，关闭时不产生任何模型调用。</p>
 *
 * @author Li
 * @since 2026-11-20
 */
@Component
@Conditional(AiModelEnabledCondition.class)
public class AiExperienceJob {

    private static final Logger log = LoggerFactory.getLogger(AiExperienceJob.class);

    private final ExperienceExtractionService extraction;
    private final ExperienceMergeService merge;
    private final AiExperienceProperties properties;

    /**
     * 注入提取、合并与配置。
     *
     * @param extraction 提取服务
     * @param merge 合并服务
     * @param properties 经验配置
     */
    public AiExperienceJob(ExperienceExtractionService extraction, ExperienceMergeService merge,
                           AiExperienceProperties properties) {
        this.extraction = extraction;
        this.merge = merge;
        this.properties = properties;
    }

    /** 每天 03:10 执行；cron 可通过配置覆盖。 */
    @Scheduled(cron = "${erp.ai.experience.cron:0 10 3 * * ?}")
    public void run() {
        if (!properties.enabled()) {
            return;
        }
        try {
            extraction.extractPending();
        } catch (RuntimeException error) {
            log.error("经验提取任务失败 errorType={}", error.getClass().getName());
        }
        try {
            merge.mergeAll();
        } catch (RuntimeException error) {
            log.error("经验合并任务失败 errorType={}", error.getClass().getName());
        }
    }
}