package com.qiheng.erp.ai.service.data.registry;

import com.qiheng.erp.ai.config.AiModelEnabledCondition;
import com.qiheng.erp.ai.service.data.schema.SchemaCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * 启动时同步一次元数据表结构。
 *
 * <p>同步失败只告警不抛异常——元数据是辅助信息，它坏了不该拖垮整个 ERP 启动。
 * 同步失败时沿用已有登记，下一轮取数时守卫层会按实际列名校验，把过期列名挡下来。</p>
 *
 * <p>失效的表间关系不会自愈（模型会照着它生成必然失败的 SQL），因此送到系统异常通道，
 * 工作台会出现待办由人工修正；同步服务本身只负责发现，不做上报动作。</p>
 *
 * @author Li
 * @since 2026-10-21
 */
@Slf4j
@Component
@Conditional(AiModelEnabledCondition.class)
@RequiredArgsConstructor
public class SchemaSyncRunner implements ApplicationRunner {

    private final SchemaSyncService syncService;
    private final SchemaCache schemaCache;
    private final SchemaIssueReporter issueReporter;

    @Override
    public void run(ApplicationArguments args) {
        try {
            SchemaSyncReport report = syncService.sync();
            log.info("AI 数据表元数据同步完成 新增列={} 刷新结构={} 软删列={} 刷新索引={} 失效关系={}",
                    report.insertedColumns(), report.updatedColumns(), report.softDeletedColumns(),
                    report.updatedIndexes(), report.invalidRelations().size());
            if (report.changed()) {
                // 元数据变了，已经缓存的 Schema 文本不再反映库结构
                schemaCache.clear();
            }
            issueReporter.reportInvalidRelations(report.invalidRelations());
        } catch (Exception error) {
            log.warn("AI 数据表元数据同步失败，本次沿用已有登记", error);
        }
    }
}
