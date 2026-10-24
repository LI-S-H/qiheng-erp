package com.qiheng.erp.ai.domain.dto.needs;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 一条需求。
 *
 * <p>需求数组是 planner 的唯一产出物，也是主循环的唯一驱动来源：后续轮次的
 * "还缺什么"退化为证据项 id 与证据链 key 的确定性差集，不再由模型回忆。</p>
 *
 * <p><b>状态不进模型协议</b>：{@code status} 由代码节点维护，模型碰不到状态机。</p>
 *
 * <p><b>需求只增不改</b>：需求 id 一旦定稿就不可改写，同 id 只能追加新的证据项。
 * 证据项 id 同时是证据链的 key，已采证据是按当时的 instruction 采的，改写会让
 * 已采证据与新要求对不上。</p>
 *
 * @author Li
 * @since 2026-10-22
 */
public record Need(

        // 需求编号，依赖关系靠它；N 段是用户需求，D 段是派生需求。
        @JsonProperty(required = true)
        @JsonPropertyDescription("需求编号，全局唯一；以 N 开头为用户需求，D 开头为派生需求（由已确认方案展开而来）")
        @NotBlank
        String id,

        // 要事实还是要建议，决定这条需求走主循环还是方案链路。
        @JsonProperty(required = true)
        @JsonPropertyDescription("DATA=要事实（如查库存、统计销量）；ADVICE=要建议或方案（如缺货了给什么补货建议）")
        @NotNull
        NeedIntent intent,

        // 这个需求要干什么，一句话。
        @JsonProperty(required = true)
        @JsonPropertyDescription("这条需求要回答什么问题，一句话")
        @NotBlank
        String need,

        // 依赖哪些需求先完成。
        @JsonProperty(required = true)
        @JsonPropertyDescription("依赖哪些需求 id：这些需求先完成，本需求才能开始；没有依赖填空数组")
        @NotNull
        List<@NotBlank String> depends,

        // 条件型需求的开关；不设条件时为 null。
        // 声明成非必填：这一项可省略，省略即 null。
        // 注意：输出用 JSON Schema 是按 record 构造器生成的，record 分量一律进 required，
        // 所以"没有条件填 null"这条规则只能靠下面的 description 告诉模型。
        @JsonProperty(value = "condition", required = false)
        @JsonPropertyDescription("条件开关：决定这条需求要不要做。没有条件时为 null 或省略这个字段，不要写空对象")
        @Valid
        NeedCondition condition,

        // 这个需求要产出的全部证据项，输入与计算产物都在清单里。
        @JsonProperty(value = "evidence_plan", required = true)
        @JsonPropertyDescription("这条需求要产出的全部证据项（至少一项）：查来的原始数据、算出来的中间量和结论量都列在这里；每一项都必须产出，缺任何一项这条需求都不算完成")
        @NotEmpty
        List<@NotNull @Valid EvidenceItem> evidencePlan
) {
}
