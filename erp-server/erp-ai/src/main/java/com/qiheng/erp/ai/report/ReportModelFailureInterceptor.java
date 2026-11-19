package com.qiheng.erp.ai.report;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;

/** 同步模型调用没有正常响应时终止，避免把异常当作报告。 */
final class ReportModelFailureInterceptor extends ModelInterceptor {
    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        ModelResponse response = handler.call(request);
        if (response.getChatResponse() == null) {
            throw new IllegalStateException("报告模型调用失败");
        }
        return response;
    }

    @Override
    public String getName() {
        return "report_model_failure";
    }
}
