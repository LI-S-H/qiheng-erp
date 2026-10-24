package com.qiheng.erp.ai.domain.dto.needs;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * planner 的统一输出协议：需求数组 + 澄清请求。
 *
 * <p>不含完成判定。模型没有宣布需求完成的权力，完成判定由代码节点做。</p>
 *
 * @author Li
 * @since 2026-10-22
 */
public record PlannerOutput(

        // 本轮拆出的需求；请求澄清时为空数组。
        @JsonProperty(required = true)
        @JsonPropertyDescription("本轮拆出的需求数组；请求澄清时为空数组")
        @NotNull
        List<@NotNull @Valid Need> needs,

        // 必须显式填写，不能把缺省 false 当作信息已经充分。
        @JsonProperty(value = "needs_clarification", required = true)
        @JsonPropertyDescription("是否缺少必须由用户确认的信息，不表示是否已经取得数据库事实")
        @NotNull
        Boolean needsClarification,

        // 结构化澄清问题；不需要澄清时为空数组。
        @JsonProperty(value = "questions", required = true)
        @JsonPropertyDescription("结构化澄清问题数组；不需要澄清时为空数组")
        @NotNull
        List<@NotNull @Valid ClarificationQuestion> questions
) {
}
