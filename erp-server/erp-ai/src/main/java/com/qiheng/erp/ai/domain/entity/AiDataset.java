package com.qiheng.erp.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 数据集元信息；时间统一以 UTC 的本地日期时间形式持久化。 */
@Data
@TableName("ai_dataset")
public class AiDataset {
    @TableId(value = "dataset_id", type = IdType.INPUT)
    private String datasetId;
    private String sqlRunId;
    private Integer attempt;
    private String readyRunId;
    private Long userId;
    private Long conversationId;
    private String description;
    private String sourceSql;
    private String columnsJson;
    private String dataDescriptionJson;
    private Integer rowCount;
    private Boolean completed;
    private String truncationReason;
    private String storageFile;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}
