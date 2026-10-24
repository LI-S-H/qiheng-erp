package com.qiheng.erp.ai.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 产物内容事件体：{@code artifact} 事件载荷，由发布拍写入 State 的产物投影转发而来。
 *
 * <p>content 的形状与落库消息 content（VisibleMessageContent）完全同源，
 * 前端可复用同一套渲染协议。</p>
 *
 * @param type         产物类型，当前为 PLAN 与 CLARIFICATION
 * @param checkpointId 产物定稿拍对应的检查点标识，仅作诊断（恢复锚点为 content.data.pauseId）
 * @param content      产物展示内容，与可见消息 content 同构
 * @author Li
 * @since 2026-10-17
 */
@Schema(description = "AI 产物内容事件")
public record RunArtifactEventVO(
        @Schema(description = "产物类型，与可见消息 content.type 同源") String type,
        @Schema(description = "产物对应检查点标识，仅作诊断，不可得时为空") String checkpointId,
        @Schema(description = "产物展示内容，与可见消息 content 同构") Object content
) {
}
