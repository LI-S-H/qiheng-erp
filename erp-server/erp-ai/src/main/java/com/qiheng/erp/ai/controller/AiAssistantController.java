package com.qiheng.erp.ai.controller;

import com.qiheng.erp.ai.domain.dto.AiChatRequest;
import com.qiheng.erp.ai.domain.dto.AiConversationUpdateRequest;
import com.qiheng.erp.ai.domain.vo.AiAssistantOverviewVO;
import com.qiheng.erp.ai.domain.vo.AiChatMessageVO;
import com.qiheng.erp.ai.domain.vo.AiChatResponseVO;
import com.qiheng.erp.ai.domain.vo.AiConversationSummaryVO;
import com.qiheng.erp.ai.service.IAiAssistantService;
import com.qiheng.erp.ai.service.IAiConversationService;
import com.qiheng.erp.ai.service.IAiMessageService;
import com.qiheng.erp.common.dto.PageQuery;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** HTTP 接口只负责参数校验和协议适配，消息生命周期统一由 Service 管理。 */
@RestController
@RequestMapping("/ai/assistant")
@Tag(name = "智能经营助手")
public class AiAssistantController {
    private final IAiConversationService conversationService;
    private final IAiMessageService messageService;
    private final IAiAssistantService assistantService;

    public AiAssistantController(IAiConversationService conversationService,
                                 IAiMessageService messageService,
                                 IAiAssistantService assistantService) {
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.assistantService = assistantService;
    }

    @GetMapping("/overview")
    @Operation(summary = "获取智能经营助手概览")
    public Result<AiAssistantOverviewVO> overview() {
        return Result.ok(conversationService.overview());
    }

    @PostMapping(value = "/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "发送消息并等待最终回复")
    public Result<AiChatResponseVO> sendMessage(@Valid @RequestBody AiChatRequest request) {
        return Result.ok(assistantService.send(request));
    }

    @PostMapping(value = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "发送消息并接收 SSE 流式回复")
    public SseEmitter streamMessage(@Valid @RequestBody AiChatRequest request) {
        return assistantService.stream(request);
    }

    @PostMapping("/conversations")
    @Operation(summary = "新建智能经营助手会话")
    public Result<AiConversationSummaryVO> createConversation() {
        return Result.ok(conversationService.create());
    }

    @PatchMapping("/conversations/{conversationId}")
    @Operation(summary = "修改智能经营助手会话名称")
    public Result<AiConversationSummaryVO> updateConversation(@PathVariable Long conversationId,
            @Valid @RequestBody AiConversationUpdateRequest request) {
        return Result.ok(conversationService.update(conversationId, request));
    }

    @DeleteMapping("/conversations/{conversationId}")
    @Operation(summary = "删除智能经营助手会话")
    public Result<Void> deleteConversation(@PathVariable Long conversationId) {
        conversationService.delete(conversationId);
        return Result.ok();
    }

    @GetMapping("/conversations/{conversationId}/messages")
    @Operation(summary = "分页查询会话历史消息")
    public Result<PageResult<AiChatMessageVO>> history(@PathVariable Long conversationId, @Valid PageQuery query) {
        return Result.ok(messageService.pageHistory(conversationId, query));
    }
}
