package com.qiheng.erp.ai.agent;

import com.qiheng.erp.ai.clarification.ClarificationTestAccess;

import com.qiheng.erp.ai.clarification.ClarificationService;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.qiheng.erp.ai.clarification.ClarificationAnswer;
import reactor.core.publisher.Flux;
import java.util.List;

/** 集成测试使用的可信身份入口，不作为生产接口暴露。 */
public final class AgentTestAccess {
    private AgentTestAccess() {}

    public static Flux<NodeOutput> stream(MainAgentService service, long userId, long conversationId, String input) throws Exception {
        return service.streamForUser(userId, conversationId, input);
    }

    public static void answer(ClarificationService service, long userId, long conversationId, String id, List<ClarificationAnswer> answers, String note) {
        ClarificationTestAccess.answer(service, userId, conversationId, id, answers, note);
    }
}
