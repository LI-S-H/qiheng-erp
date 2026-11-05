package com.qiheng.erp.ai.agent.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.qiheng.erp.ai.knowledge.graph.BusinessGraphProvider;
import org.springframework.ai.chat.messages.SystemMessage;

/** 在每次模型调用时注入当前登记的业务图，不追加到会话历史。 */
public final class BusinessGraphInterceptor extends ModelInterceptor {
    private final BusinessGraphProvider graph;

    public BusinessGraphInterceptor(BusinessGraphProvider graph) {
        this.graph = graph;
    }

    /** 仅修改本次模型请求，避免共享 Agent 或历史消息被永久改写。 */
    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        String instruction = request.getSystemMessage() == null ? "" : request.getSystemMessage().getText();
        Object date = request.getContext().get("businessDate");
        String time = date == null ? "" : "\n\n【本轮时间上下文】\n当前业务日期：" + date
                + "，时区：Asia/Shanghai。相对时间默认以此日期为基准；用户明确选择其他基准时，按其选择执行并说明。";
        return handler.call(ModelRequest.builder(request)
                .systemMessage(new SystemMessage(instruction + time + "\n\n" + graph.render())).build());
    }
    @Override
    public String getName() {
        return "business_graph";
    }
}
