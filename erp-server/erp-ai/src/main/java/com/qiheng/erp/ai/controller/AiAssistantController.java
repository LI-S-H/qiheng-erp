package com.qiheng.erp.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.qiheng.erp.ai.domain.dto.ConversationUpdateRequest;
import com.qiheng.erp.ai.domain.vo.AssistantOverviewVO;
import com.qiheng.erp.ai.domain.vo.ConversationDetailVO;
import com.qiheng.erp.ai.domain.vo.ConversationMessagePageVO;
import com.qiheng.erp.ai.service.AiAssistantService;
import com.qiheng.erp.ai.service.GraphService;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.common.util.IdUtil;
import com.qiheng.erp.security.context.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * AI 助手 HTTP 接口：会话管理与会话历史查询。
 *
 * <p>页面级入口权限 {@code ai:assistant:use}：整个控制器共用一个权限码，
 * 后续若需要按端点细分再加方法级注解。</p>
 *
 * @author Li
 * @since 2026-10-15
 */
@RestController
@RequestMapping("/ai/assistant")
@RequiredArgsConstructor
@SaCheckPermission("ai:assistant:use")
@Tag(name = "AI 助手")
public class AiAssistantController {

    private final AiAssistantService aiAssistantService;
    private final GraphService graphService;

    /**
     * AI 助手概览：当前用户最近的会话列表。
     */
    @GetMapping("/overview")
    @Operation(summary = "AI 助手概览：当前用户最近的会话列表")
    public Result<AssistantOverviewVO> overview() {
        Long userId = UserContext.requireCurrentUser().getUserId();
        return Result.ok(aiAssistantService.overview(userId));
    }

    /**
     *  新建 AI 会话。
     * @return
     */
    @PostMapping("/conversations")
    @Operation(summary = "新建 AI 会话")
    public Result<ConversationDetailVO> create() {
        Long userId = UserContext.requireCurrentUser().getUserId();
        return Result.ok(aiAssistantService.create(userId));
    }

    /**
     *  会话详情，含运行进度快照。
     * @return
     */
    @GetMapping("/conversations/{id}")
    @Operation(summary = "会话详情，含运行进度快照")
    public Result<ConversationDetailVO> detail(@PathVariable("id") String id) {
        Long userId = UserContext.requireCurrentUser().getUserId();
        Long conversationId = IdUtil.parseRequiredLongId(id, "id");
        return Result.ok(aiAssistantService.detail(conversationId, userId));
    }

    /**
     *  重命名会话。
     */
    @PatchMapping("/conversations/{id}")
    @Operation(summary = "重命名会话")
    public Result<Void> rename(@PathVariable("id") String id,
                                @Valid @RequestBody ConversationUpdateRequest request) {
        Long userId = UserContext.requireCurrentUser().getUserId();
        Long conversationId = IdUtil.parseRequiredLongId(id, "id");
        aiAssistantService.rename(conversationId, userId, request.title().trim());
        return Result.ok();
    }

    /**
     *  删除会话：先删消息再删会话，同事务。
     */
    @DeleteMapping("/conversations/{id}")
    @Operation(summary = "删除会话：先删消息再删会话，同事务")
    public Result<Void> delete(@PathVariable("id") String id) {
        Long userId = UserContext.requireCurrentUser().getUserId();
        Long conversationId = IdUtil.parseRequiredLongId(id, "id");
        aiAssistantService.delete(conversationId, userId);
        return Result.ok();
    }

    /**
     *  用户可见消息分页。
     * @return
     */
    @GetMapping("/conversations/{id}/messages")
    @Operation(summary = "用户可见消息分页")
    public Result<ConversationMessagePageVO> pageMessages(@PathVariable("id") String id,
                                                        @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
                                                        @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        Long userId = UserContext.requireCurrentUser().getUserId();
        Long conversationId = IdUtil.parseRequiredLongId(id, "id");
        return Result.ok(aiAssistantService.pageMessages(conversationId, userId, pageNum, pageSize));
    }

    /**
     *  订阅会话正在执行的进度；没有在跑的任务时立即结束。
     */
    @GetMapping(value = "/conversations/{id}/progress/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅会话正在执行的进度；没有在跑的任务时立即结束")
    public Flux<ServerSentEvent<Object>> progressStream(@PathVariable("id") String id) {
        Long conversationId = IdUtil.parseRequiredLongId(id, "id");
        return graphService.subscribeProgress(conversationId);
    }
}
