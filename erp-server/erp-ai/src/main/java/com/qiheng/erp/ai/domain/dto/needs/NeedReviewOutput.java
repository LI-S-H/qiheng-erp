package com.qiheng.erp.ai.domain.dto.needs;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * need_review 的输出协议：业务完整性审阅结论。
 *
 * <p>只回答"拆得对不对"，不回答"数据对不对"——后者是取数之后的事。</p>
 *
 * @author Li
 * @since 2026-10-22
 */
public record NeedReviewOutput(

        @JsonProperty(required = true)
        @NotNull
        NeedReviewVerdict verdict,

        // 问题清单；verdict=OK 时为空数组。
        @JsonProperty(required = true)
        @NotNull
        List<@NotNull @Valid NeedReviewIssue> issues
) {
}
