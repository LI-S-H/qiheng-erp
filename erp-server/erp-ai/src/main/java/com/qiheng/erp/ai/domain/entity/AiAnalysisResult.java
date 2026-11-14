package com.qiheng.erp.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 分析结果文件的元信息，时间使用 UTC。 */
@Data
@TableName("ai_analysis_result")
public class AiAnalysisResult {
    @TableId(value = "result_id", type = IdType.INPUT)
    private String resultId;
    private Long userId;
    private Long conversationId;
    private String analysisTaskId;
    private String executionId;
    private String resultType;
    private String name;
    private String description;
    private String fileType;
    private Long fileSize;
    private String storageFile;
    private String sourceDatasetIds;
    private LocalDateTime createdAt;
    /** UTC 过期时刻；空表示不自动过期。 */
    private LocalDateTime expiresAt;
}
