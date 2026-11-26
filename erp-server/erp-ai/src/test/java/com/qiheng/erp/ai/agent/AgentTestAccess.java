package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import reactor.core.publisher.Flux;

/** 集成测试使用的可信身份入口，不作为生产接口暴露。 */
public final class AgentTestAccess {
    private AgentTestAccess() {}

    public static Flux<NodeOutput> stream(MainAgentService service, long userId, long conversationId, String input) throws Exception {
        return service.streamForUser(userId, conversationId, input);
    }
}