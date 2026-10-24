package com.qiheng.erp.ai.service.impl;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.qiheng.erp.ai.domain.dto.GraphResumeRequest;
import com.qiheng.erp.ai.domain.dto.GraphStartRequest;
import com.qiheng.erp.ai.domain.vo.RunStateVO;
import com.qiheng.erp.ai.service.GraphService;
import com.qiheng.erp.ai.graph.support.PermissionCodes;
import com.qiheng.erp.ai.graph.runtime.RunCoordinator;
import com.qiheng.erp.ai.graph.runtime.RunStateResolver;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.exception.ServiceUnavailableBizException;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 图任务应用入口：只做身份校验、图可用性守卫与委派。
 *
 * <p>执行编排在 {@link RunCoordinator}，状态读取在 {@link RunStateResolver}；
 * 本类不承载业务规则，入口签名的变化与执行细节的演进互不影响。</p>
 *
 * @author Li
 * @since 2026-10-08
 */
@Service
@RequiredArgsConstructor
public class GraphServiceImpl implements GraphService {

    private final ObjectProvider<CompiledGraph> graphs;
    private final RunCoordinator coordinator;
    private final RunStateResolver stateResolver;

    /** 提交新任务。 */
    @Override
    public Flux<ServerSentEvent<Object>> stream(GraphStartRequest request) {
        LoginUser user = UserContext.requireCurrentUser();
        requireGraph();
        return coordinator.submit(user.getUserId(), PermissionCodes.of(user), request, null);
    }

    /** 恢复任务。 */
    @Override
    public Flux<ServerSentEvent<Object>> streamResume(GraphResumeRequest request) {
        LoginUser user = UserContext.requireCurrentUser();
        requireGraph();
        return coordinator.submit(user.getUserId(), PermissionCodes.of(user), null, request);
    }

    /**
     *
     * */
    @Override
    public Flux<ServerSentEvent<Object>> subscribeProgress(Long conversationId) {
        return coordinator.subscribeProgress(conversationId, UserContext.requireCurrentUser().getUserId());
    }

    @Override
    public RunStateVO runState(Long conversationId, Long userId) {
        return stateResolver.resolve(conversationId, userId);
    }

    /**
     * 图实例由配置条件装配：未启用或缺少密钥时入口直接拒绝，不进入执行链路。
     */
    private void requireGraph() {
        if (graphs.getIfAvailable() == null) {
            throw new ServiceUnavailableBizException(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }
    }
}
