package com.qiheng.erp.ai.service;

import com.qiheng.erp.ai.domain.dto.GraphResumeRequest;
import com.qiheng.erp.ai.domain.dto.GraphStartRequest;
import com.qiheng.erp.ai.domain.vo.RunStateVO;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/** 图任务应用入口，后续扩展执行与报告流程时不改变入口职责。 */
public interface GraphService {

    /**
     * 流式执行一轮规划，边执行边推送节点进度。
     *
     * @param request 会话与需求
     * @return SSE 事件流，结束时发出 result 或 error
     */
    Flux<ServerSentEvent<Object>> stream(GraphStartRequest request);

    /**
     * 流式提交补充回答并恢复图任务。
     *
     * @param request 线程、暂停点与回答
     * @return SSE 事件流，结束时发出 result 或 error
     */
    Flux<ServerSentEvent<Object>> streamResume(GraphResumeRequest request);

    /**
     * 接续订阅会话正在执行的进度；没有在跑的任务时立即结束。
     *
     * @param conversationId 会话 ID
     * @return SSE 事件流
     */
    Flux<ServerSentEvent<Object>> subscribeProgress(Long conversationId);

    /**
     * 读取会话当前的运行状态，用于页面重新进入时还原进度显示。
     * 活动任务用于判断执行中，最近用户请求及其检查点、展示流水用于判断暂停或终态。
     *
     * @param conversationId 会话 ID
     * @param userId         当前登录用户
     * @return 当前任务状态；没有任务历史时返回 {@link RunStateVO#IDLE}
     */
    RunStateVO runState(Long conversationId, Long userId);
}
