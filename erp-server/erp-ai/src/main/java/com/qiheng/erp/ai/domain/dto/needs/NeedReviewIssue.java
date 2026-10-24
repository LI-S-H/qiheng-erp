package com.qiheng.erp.ai.domain.dto.needs;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * 一条业务完整性问题。
 *
 * <p>{@code suggestion} 必须具体到可执行：写"缺华南仓 A 物料可用库存"才改得了
 * 清单，写"信息不足"在结构校验节点就拦下。</p>
 *
 * @author Li
 * @since 2026-10-22
 */
public record NeedReviewIssue(

        // 出问题的需求编号。
        @JsonProperty(value = "need_id", required = true)
        @NotBlank
        String needId,

        // 问题是什么。
        @JsonProperty(required = true)
        @NotBlank
        String problem,

        // 建议怎么改，必须具体到可执行。
        @JsonProperty(required = true)
        @NotBlank
        String suggestion
) {
}
