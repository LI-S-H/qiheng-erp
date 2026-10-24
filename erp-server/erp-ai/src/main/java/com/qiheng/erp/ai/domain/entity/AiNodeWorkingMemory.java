package com.qiheng.erp.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * AI 节点工作记忆表
 * </p>
 *
 * <p>节点每次执行读取的就是这张表，按 {@code sort_order} 升序得到完整迭代历史。
 * 行正文是「模型视角的纯文本转写」：一行 = 模型的一条消息，行首标记说明角色
 * （{@code [输入]} / {@code [输出]} / {@code [摘要]}），正文与真正发给模型的内容一字不差。
 * 压缩只改写这张表：把最早的若干条替换成一条摘要，原文仍保留在 {@code ai_node_memory} 中。</p>
 *
 * @author Li
 * @since 2026-10-15
 */
@Data
@Accessors(chain = true)
@TableName("ai_node_working_memory")
@Schema(name = "AiNodeWorkingMemory", description = "AI 节点工作记忆表")
public class AiNodeWorkingMemory implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "工作记忆 ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "所属会话 ID")
    @TableField("conversation_id")
    private Long conversationId;

    @Schema(description = "所属用户 ID")
    @TableField("user_id")
    private Long userId;

    @Schema(description = "节点编码：PLANNER、SQL_GENERATE、PYTHON_GENERATE、REPORTER、REFLECTION")
    @TableField("node_code")
    private String nodeCode;

    @Schema(description = "节点内排序号；压缩窗口使用历史最大值之后的新序号保持顺序")
    @TableField("sort_order")
    private Long sortOrder;

    @Schema(description = "纯文本转写：模型视角的一次输入或一次输出，首行为 [输入]/[输出]/[摘要] 标记")
    @TableField("content")
    private String content;

    @Schema(description = "来源原文 ID 数组；摘要合并来源引用，便于追溯")
    @TableField("source_memory_ids")
    private String sourceMemoryIds;

    @Schema(description = "创建时间")
    @TableField("create_time")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField("update_time")
    private LocalDateTime updateTime;

    @Schema(description = "逻辑删除：0 未删除，1 已删除")
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
