package com.qiheng.erp.ai.returnorder;

import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.returnorder.domain.vo.ReturnableSourceDetailVo;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 可退明细查询工具：只读返回来源单表头与每行可退上限，供模型定量和预检共用。
 *
 * @author Li
 * @since 2026-11-24
 */
public final class ReturnableItemTool {

    private final ReturnableItemQueryService queries;

    /**
     * 注入可退来源查询服务。
     *
     * @param queries 查询服务
     */
    public ReturnableItemTool(ReturnableItemQueryService queries) {
        this.queries = queries;
    }

    /**
     * 查询退货来源单的可退明细。
     *
     * @param request 退货方向与来源单号
     * @param context 工具上下文
     * @return 来源单与可退明细；权限或参数不合法时返回错误文本
     */
    @Tool(name = "query_returnable_items", description = "查询某张来源订单当前还能退多少。"
            + "创建退货单草稿前必须先调用：传退货方向和来源单号，返回来源单状态、仓库、每行已履行量、已占用、仓库可用库存和最终可退量。"
            + "退货数量不能超过返回的最终可退量；查不到来源单或可退为 0 时，向用户说明原因，不要编造数量。")
    public Object query(
            @ToolParam(description = "退货方向与来源单号")
            ReturnableItemQuery request,
            ToolContext context) {
        AgentToolContext identity = ToolContextHelper.getMetadata(context, AgentToolContext.CONTEXT_KEY, AgentToolContext.class)
                .orElseThrow(() -> new IllegalStateException("缺少可信可退明细工具上下文"));
        try {
            ReturnableSourceDetailVo source = queries.load(identity.userId(), request.returnType(), request.sourceOrderNo());
            return source;
        } catch (IllegalArgumentException | BizException error) {
            return error.getMessage();
        }
    }
}