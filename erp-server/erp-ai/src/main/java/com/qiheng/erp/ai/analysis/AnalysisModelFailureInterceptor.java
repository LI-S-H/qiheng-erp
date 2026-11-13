package com.qiheng.erp.ai.analysis;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;

/**
 * 当前同步调用中，框架将模型异常转换为无 ChatResponse 的消息。
 * 流式调用或引入模型降级拦截器时，需重新确认该判断方式。
 *
 * @author Li
 * @since 2026-11-10
 */
final class AnalysisModelFailureInterceptor extends ModelInterceptor {
    /**
     * 模型没有返回有效响应时立即失败，避免把故障提示当成分析结论。
     *
     * @param request 本次模型请求
     * @param handler 后续拦截器与模型的调用链
     * @return 模型响应
     */
    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        ModelResponse response = handler.call(request);
        if (response.getChatResponse() == null) {
            throw new IllegalStateException("分析模型调用失败");
        }
        return response;
    }

    /** 提供拦截器在分析任务中的名称。 */
    @Override
    public String getName() {
        return "analysis_model_failure";
    }
}
