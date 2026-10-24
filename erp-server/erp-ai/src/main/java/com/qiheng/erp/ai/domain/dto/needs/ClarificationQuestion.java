package com.qiheng.erp.ai.domain.dto.needs;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 人工澄清协议：候选项由模型明确提供，前端始终允许自定义补充。 */
public record ClarificationQuestion(

        // 标识只用于当前一轮问题关联，不充当业务资源 ID。
        @JsonProperty(required = true)
        @JsonPropertyDescription("问题标识，本轮内唯一，用于把用户的回答对回这道题")
        @NotBlank
        String id,

        // 完整问题正文供用户理解，也随回答一起进入任务历史。
        @JsonProperty(required = true)
        @JsonPropertyDescription("要问用户什么：一句话问句，用户看得懂")
        @NotBlank
        String question,

        // 空数组表示自由输入；非空数组是单选建议，不能杜撰仓库等业务实体。
        @JsonProperty(required = true)
        @JsonPropertyDescription("单选候选文本；未知真实业务选项时必须为空数组，始终允许用户自行补充")
        @NotNull
        List<@NotBlank String> options
) {
}
