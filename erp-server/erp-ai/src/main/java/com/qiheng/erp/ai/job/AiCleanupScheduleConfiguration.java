package com.qiheng.erp.ai.job;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 孤儿文件可能整夜扫描，使用独立调度线程避免阻塞其他定时任务。 */
@Configuration(proxyBeanMethods = false)
public class AiCleanupScheduleConfiguration {
    /** 保留其他定时任务的默认调度器，不与长时间文件扫描共用。 */
    @Bean("taskScheduler")
    @ConditionalOnMissingBean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ai-scheduled-");
        return scheduler;
    }

    @Bean("aiOrphanCleanupScheduler")
    public ThreadPoolTaskScheduler aiOrphanCleanupScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ai-orphan-cleanup-");
        return scheduler;
    }
}
