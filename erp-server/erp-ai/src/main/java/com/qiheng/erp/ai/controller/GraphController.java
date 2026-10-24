package com.qiheng.erp.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.qiheng.erp.ai.domain.dto.GraphResumeRequest;
import com.qiheng.erp.ai.domain.dto.GraphStartRequest;
import com.qiheng.erp.ai.service.GraphService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 图任务 HTTP 入口；控制器只做请求校验和统一响应封装。
 *
 * <p>启动与恢复共用一种交付方式：在同一连接里依次推送 node-start、node-end，
 * 最后以 result 或 error 收尾，供页面显示运行进度。</p>
 *
 * @author Li
 * @since 2026-10-08
 */
@RestController
@RequestMapping("/ai/graph")
@SaCheckPermission("ai:assistant:use")
@Tag(name = "AI 图任务")
public class GraphController {

    private final GraphService graphService;

    public GraphController(GraphService graphService) {
        this.graphService = graphService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "启动 AI 图任务并流式推送节点进度")
    public Flux<ServerSentEvent<Object>> stream(@Valid @RequestBody GraphStartRequest request) {
        return graphService.stream(request);
    }

    @PostMapping(value = "/resume/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "提交补充回答并流式推送恢复后的节点进度")
    public Flux<ServerSentEvent<Object>> resumeStream(@Valid @RequestBody GraphResumeRequest request) {
        return graphService.streamResume(request);
    }
}
