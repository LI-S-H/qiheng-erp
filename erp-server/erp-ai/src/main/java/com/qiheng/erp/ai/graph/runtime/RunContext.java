package com.qiheng.erp.ai.graph.runtime;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;

import java.util.Map;

/**
 * 一次图运行的不可变上下文：身份、图实例与执行输入。
 *
 * <p>启动与恢复的差异只在 {@link #start} 与 {@link #resume} 两个工厂方法里表达；
 * 运行期间的进度通道与耗时簿记不属于本对象，由进度追踪器按运行创建持有。</p>
 *
 * @param graph          编译后的主图
 * @param conversationId 会话 ID
 * @param userId         发起人，异步线程不再访问登录上下文
 * @param requestId      本轮请求标识，与检查点、消息、节点记忆关联
 * @param threadId       检查点线程标识
 * @param config         本次执行使用的配置，恢复场景已注入人工回答
 * @param resumed        是否为恢复执行
 * @param initialState   首次执行的初始状态；恢复场景为 null，起点完全由检查点决定
 * @author Li
 * @since 2026-10-16
 */
public record RunContext(
        CompiledGraph graph,
        long conversationId,
        long userId,
        String requestId,
        String threadId,
        RunnableConfig config,
        boolean resumed,
        Map<String, Object> initialState
) {

    /** 构造首次执行的运行上下文。 */
    public static RunContext start(CompiledGraph graph, long conversationId, long userId, String requestId,
                                   String threadId, Map<String, Object> initialState) {
        return new RunContext(graph, conversationId, userId, requestId, threadId,
                RunnableConfig.builder().threadId(threadId).build(), false, initialState);
    }

    /** 构造从检查点恢复的运行上下文；起点完全由检查点决定。 */
    public static RunContext resume(CompiledGraph graph, long conversationId, long userId, String requestId,
                                    String threadId, RunnableConfig resumedConfig) {
        return new RunContext(graph, conversationId, userId, requestId, threadId, resumedConfig, true, null);
    }

    /** 本次执行实际使用的配置：恢复场景附加上恢复标记。 */
    public RunnableConfig execConfig() {
        return resumed ? config.withResume() : config;
    }
}
