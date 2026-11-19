package com.qiheng.erp.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.qiheng.erp.ai.report.ReportStore;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.common.util.IdUtil;
import com.qiheng.erp.security.context.UserContext;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 提供会话历史卡片与正式报告详情，身份始终来自登录上下文。 */
@RestController
@RequestMapping("/ai/assistant/conversations/{conversationId}/reports")
@SaCheckPermission("ai:assistant:use")
public class AiReportController {
    private final ReportStore reports;

    public AiReportController(ReportStore reports) {
        this.reports = reports;
    }

    /** 历史恢复只查卡片，支持一次传入当前消息页的多个轮次。 */
    @GetMapping
    public Result<List<ReportStore.Reference>> cards(@PathVariable String conversationId,
            @RequestParam(name = "requestId", required = false) List<String> requestIds) {
        long id = IdUtil.parseRequiredLongId(conversationId, "conversationId");
        return Result.ok(reports.cards(id, UserContext.requireCurrentUser().getUserId(), requestIds));
    }

    /** 只有用户打开详情时才返回完整正文。 */
    @GetMapping("/{reportId}")
    public Result<ReportStore.Detail> detail(@PathVariable String conversationId, @PathVariable String reportId) {
        long id = IdUtil.parseRequiredLongId(conversationId, "conversationId");
        return Result.ok(reports.detail(id, UserContext.requireCurrentUser().getUserId(), reportId));
    }
}
