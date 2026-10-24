package com.qiheng.erp.ai.domain.vo;

import com.qiheng.erp.ai.constant.NodeLabels;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 节点进度事件体：{@code node-start} 时 durationMs 为 null，{@code node-end} 时为本节点耗时。
 *
 * <p>耗时用 Integer 而不是 Long：全局 Jackson 会把 Long 序列化成字符串以避免 BIGINT 精度问题，
 * 而耗时是纯数值，前端需要按数字读取。</p>
 *
 * @param node       节点编码，前端不硬编码
 * @param label      节点展示文案
 * @param durationMs 节点耗时毫秒，节点开始时为 null
 * @author Li
 * @since 2026-10-16
 */
@Schema(description = "AI 节点进度")
public record RunNodeEventVO(
        @Schema(description = "节点编码") String node,
        @Schema(description = "节点展示文案") String label,
        @Schema(description = "节点耗时毫秒，节点开始时为空") Integer durationMs
) {

    /**
     * 构造节点开始事件。
     *
     * @param node 节点编码
     * @return 进度事件体
     */
    public static RunNodeEventVO started(String node) {
        return new RunNodeEventVO(node, NodeLabels.of(node), null);
    }

    /**
     * 构造节点结束事件。
     *
     * @param node           节点编码
     * @param durationMillis 节点耗时毫秒
     * @return 进度事件体
     */
    public static RunNodeEventVO finished(String node, int durationMillis) {
        return new RunNodeEventVO(node, NodeLabels.of(node), durationMillis);
    }
}
