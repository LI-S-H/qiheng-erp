package com.qiheng.erp.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 会话消息及其结构化展示数据。 */
@Data
@Accessors(chain = true)
@TableName("ai_message")
public class AiMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("conversation_id")
    private Long conversationId;

    @TableField("user_id")
    private Long userId;

    @TableField("role")
    private String role;

    @TableField("content")
    private String content;

    @TableField("charts_json")
    private String chartsJson;

    @TableField("action_cards_json")
    private String actionCardsJson;

    @TableField("sources_json")
    private String sourcesJson;

    @TableField("agent_traces_json")
    private String agentTracesJson;

    @TableField("task_card_json")
    private String taskCardJson;

    @TableField("workbench_json")
    private String workbenchJson;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
