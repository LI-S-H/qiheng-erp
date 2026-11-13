package com.qiheng.erp.ai.dataset;

import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.sql.execution.model.SqlQueryColumn;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 组装当前会话的已授权数据集说明与最多两行预览。
 *
 * @author Li
 * @since 2026-11-10
 */
@Service
public final class AnalysisDatasetSummaryService {
    private final DatasetManager datasets;

    /** 注入负责归属与权限校验的数据集管理器。 */
    public AnalysisDatasetSummaryService(DatasetManager datasets) {
        this.datasets = datasets;
    }

    /** 读取预览时再次校验身份和权限，避免列表查询后授权发生变化。 */
    public Summary listForAnalysis(AgentToolContext identity) {
        List<Item> items = new ArrayList<>();
        int unavailable = 0;
        for (var record : datasets.listReady(identity.userId(), identity.conversationId())) {
            try {
                var page = datasets.readPage(record.datasetId(), identity.userId(), identity.conversationId(), 0, 2);
                items.add(new Item(record.datasetId(), record.description(), record.columns(), record.dataDescription(),
                        record.rowCount(), record.completed(), record.completenessMessage(),
                        new Preview(page.rows(), page.returnedRows(), page.hasMore(), page.truncated(), "SUCCESS")));
            } catch (DatasetReadLimitException error) {
                // 授权通过但单行过长时，保留说明并明确未能提供预览。
                items.add(new Item(record.datasetId(), record.description(), record.columns(), record.dataDescription(),
                        record.rowCount(), record.completed(), record.completenessMessage(),
                        new Preview(List.of(), 0, record.rowCount() > 0, true, "TOO_LARGE")));
            } catch (IllegalArgumentException | IllegalStateException error) {
                // 授权变化或文件不可读时，不暴露可能已失权的数据集信息。
                unavailable++;
            }
        }
        return new Summary(List.copyOf(items), unavailable,
                unavailable == 0 ? "预览最多包含前两行，不能代表全部数据。"
                        : "部分数据集在读取预览时不可用，已省略；预览最多包含前两行。");
    }

    /** 列表及本次读取预览时不可用的数据集数量。 */
    public record Summary(List<Item> datasets, int unavailableCount, String message) { }

    /** 对模型可见的数据集元信息，不包含服务器路径。 */
    public record Item(String datasetId, String description, List<SqlQueryColumn> columns,
                       SqlDataDescription dataDescription, int rowCount, boolean completed,
                       String completenessMessage, Preview preview) { }

    /** status 标记预览是否可用，hasMore 表示数据集内尚有未展示行。 */
    public record Preview(List<List<Object>> rows, int returnedRows, boolean hasMore,
                          boolean truncated, String status) { }
}
