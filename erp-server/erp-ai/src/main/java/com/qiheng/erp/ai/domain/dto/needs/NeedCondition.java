package com.qiheng.erp.ai.domain.dto.needs;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 结构化条件：需求或方案的开关。
 *
 * <p>左值取自证据链，右值只允许字面量，判定由受限比较器在代码里完成。</p>
 *
 * @author Li
 * @since 2026-10-22
 */
public record NeedCondition(

        // 形如 evidence.N1-E5，从证据链取该 key 的实际值。
        @JsonProperty(required = true)
        @JsonPropertyDescription("形如 evidence.N1-E5 的证据项引用：从证据链里取该 id 的实际值参与比较；id 必须是已经能算出来的证据项，不能引用还没查的项")
        @NotBlank
        String left,

        // 比较符白名单。
        @JsonProperty(required = true)
        @JsonPropertyDescription("比较符：GT 大于、GTE 大于等于、LT 小于、LTE 小于等于、EQ 等于、NEQ 不等于。只与字面量比较，不做算术，也不比较两个证据项；两侧类型必须一致（数字对数字、布尔对布尔、字符串对字符串），类型不一致视为条件不成立")
        @NotNull
        ComparisonOp op,

        // 右值只允许字面量：数字、布尔或字符串。
        @JsonProperty(required = true)
        @JsonPropertyDescription("字面量：数字、布尔或字符串。不能是表达式，也不能引用别的证据项")
        Object right
) {
}
