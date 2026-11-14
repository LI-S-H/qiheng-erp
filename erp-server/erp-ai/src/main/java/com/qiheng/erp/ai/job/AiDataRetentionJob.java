package com.qiheng.erp.ai.job;

import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.python.result.AnalysisResultStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.function.IntUnaryOperator;

/**
 * 每天凌晨清理已过期的取数数据集与 Python 分析结果。
 *
 * <p>两类数据的保留天数由配置决定：数据集是中间产物，结果文件是交付物，分别设置。
 * 清理按批进行，单批失败不会影响下一批；过期但清理失败的数据留待次日重试。</p>
 *
 * @author Li
 * @since 2026-11-14
 */
@Component
public class AiDataRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(AiDataRetentionJob.class);

    /** 单批处理条数，避免一次加载过多记录。 */
    private static final int BATCH_SIZE = 200;

    /** 单次运行最多处理批数，防止异常情况下长时间占用数据库。 */
    private static final int MAX_BATCHES = 50;

    private final DatasetManager datasets;
    private final AnalysisResultStore results;

    /**
     * 注入数据集与结果存储，用于删除过期文件及元信息。
     *
     * @param datasets 数据集管理器
     * @param results 分析结果存储
     */
    public AiDataRetentionJob(DatasetManager datasets, AnalysisResultStore results) {
        this.datasets = datasets;
        this.results = results;
    }

    /** 每天 03:40 清理过期数据；保留天数为 0 时对应类型不会被写入过期时刻，因而不会被清理。 */
    @Scheduled(cron = "0 40 3 * * ?")
    public void purgeExpired() {
        int datasetCount = purgeInBatches(datasets::purgeExpired);
        int resultCount = purgeInBatches(results::purgeExpired);
        log.info("AI 过期数据清理完成 datasets={} results={}", datasetCount, resultCount);
    }

    /** 分批执行清理，直到某一批不足一批即视为清空。 */
    private int purgeInBatches(IntUnaryOperator purge) {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int removed = purge.applyAsInt(BATCH_SIZE);
            total += removed;
            if (removed < BATCH_SIZE) {
                break;
            }
        }
        return total;
    }
}