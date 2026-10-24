package com.qiheng.erp.ai.domain.dto.needs;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 需求要产出的一个证据项。
 *
 * <p>每项带稳定 id，<b>这个 id 同时就是证据链的 key</b>：主循环的差集计算是
 * "证据项 id 集合 − 证据链 key 集合"，两边必须同构，否则算不出差集。</p>
 *
 * <p>证据项包含查来的原始数据，也包含算出来的中间量与结论量。计算产物也必须在
 * 清单里，否则它永远进不了差集，完成判定就永远判不满足。</p>
 *
 * @author Li
 * @since 2026-10-22
 */
public record EvidenceItem(

        // 证据项标识，全局唯一，同时是证据链的 key。
        @JsonProperty(required = true)
        @JsonPropertyDescription("证据项唯一标识，全局唯一，同时是证据链的 key；以所属需求 id 加短横线开头，如 N1-E1")
        @NotBlank
        String id,

        // 业务含义，给模型与用户看，不参与判定。
        @JsonProperty(required = true)
        @JsonPropertyDescription("这项证据的业务含义，给人和模型看，不参与判定")
        @NotBlank
        String label,

        // 取数或计算的具体要求，SQL 项写查询口径，PYTHON 项写计算式与用到的证据项 id。
        @JsonProperty(required = true)
        @JsonPropertyDescription("取数或计算的具体要求：SQL 项写清查什么、什么口径、什么时间范围、什么粒度；PYTHON 项写清计算式与用到的证据项 id")
        @NotBlank
        String instruction,

        // 产出方式，决定步骤类型与路由，由代码消费。
        @JsonProperty(required = true)
        @JsonPropertyDescription("产出方式：SQL=查库取数；PYTHON=用其它证据项算出来的计算产物；PROPOSAL=方案子图给出的方案清单（只有 ADVICE 需求用，且一条需求恰好一项）")
        @NotNull
        EvidenceSource source,

        // 依赖的其它证据项 id；计算产物必填，原始数据一般为空。
        @JsonProperty(required = true)
        @JsonPropertyDescription("依赖的其它证据项 id（可以跨需求，证据项 id 全局唯一；引用了别的需求的证据项，那条需求也要写进需求级 depends）；计算产物必填，原始数据一般为空数组")
        @NotNull
        List<@NotBlank String> depends
) {
}
