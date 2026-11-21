package com.qiheng.erp.ai.report;

import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.mapper.AiMessageMapper;
import com.qiheng.erp.ai.mapper.AiReportMapper;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 保存审核通过的报告，并提供属于当前用户的报告卡片和正文。 */
@Service
public class ReportStore {
    private static final int MAX_TITLE_LENGTH = 120;
    /** 参考资料章节标题，兼容常见同义写法。 */
    private static final Pattern REFERENCE_HEADING =
            Pattern.compile("(?m)^#{1,3}[ \\t]*(参考资料|参考文献|参考来源)[ \\t]*$");
    /** 正文中的引用编号，如 [1]、[12]。 */
    private static final Pattern CITATION_MARK = Pattern.compile("\\[\\d{1,3}]");
    /** 截断后残留的水平分隔线。 */
    private static final Pattern TRAILING_RULE = Pattern.compile("(?m)^[ \\t]*(---|\\*\\*\\*)[ \\t]*$");

    private final AiConversationMapper conversations;
    private final AiMessageMapper messages;
    private final AiReportMapper reports;

    public ReportStore(AiConversationMapper conversations, AiMessageMapper messages, AiReportMapper reports) {
        this.conversations = conversations;
        this.messages = messages;
        this.reports = reports;
    }

    /** 可以在实时报告完成事件与历史卡片列表中复用的元信息。 */
    public record Reference(String reportId, String requestId, String toolCallId, String title, String placeholder) {}

    /** 用户打开报告详情后返回的审核版本。 */
    public record Detail(String reportId, String requestId, String toolCallId, String title, String placeholder,
                         String content) {}

    /** 锁定所属会话，串行完成同轮标题分配和重复调用判断。 */
    @Transactional
    public Reference save(AgentToolContext identity, String runId, String toolCallId, String title, String content) {
        if (identity == null || runId == null || runId.isBlank() || runId.length() > 64
                || toolCallId == null || toolCallId.isBlank() || toolCallId.length() > 192
                || content == null || content.isBlank()) {
            throw new IllegalArgumentException("缺少报告归属、调用编号或正文");
        }
        long conversationId = identity.conversationId();
        long userId = identity.userId();
        if (messages.lockOwnedConversation(conversationId, userId) == null) {
            throw new BizException(ErrorCode.AI_CONVERSATION_NOT_FOUND);
        }
        AiReportMapper.CardRow existing = reports.getByCall(userId, conversationId, runId, toolCallId);
        if (existing != null) {
            return reference(existing);
        }

        String cleanTitle = normalizeTitle(title);
        String storedTitle = cleanTitle;
        for (int suffix = 2; reports.titleExists(conversationId, runId, storedTitle); suffix++) {
            String label = "（" + suffix + "）";
            storedTitle = truncate(cleanTitle, MAX_TITLE_LENGTH - label.length()) + label;
        }
        String reportId = "rpt_" + UUID.randomUUID();
        String storedContent = stripReferences(content);
        if (reports.insert(reportId, userId, conversationId, runId, toolCallId, storedTitle, storedContent) != 1) {
            throw new IllegalStateException("报告保存失败");
        }
        return new Reference(reportId, runId, toolCallId, storedTitle, placeholder(storedTitle));
    }

    /** 批量匹配当前历史消息页的轮次；未指定轮次时只读最新记录。 */
    public List<Reference> cards(long conversationId, long userId, List<String> requestIds) {
        if (conversations.selectOwned(conversationId, userId) == null) {
            throw new BizException(ErrorCode.AI_CONVERSATION_NOT_FOUND);
        }
        List<String> filter = requestIds == null ? List.of() : requestIds.stream().distinct().toList();
        if (filter.size() > 100 || filter.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 64)) {
            throw new IllegalArgumentException("报告轮次参数无效，最多支持 100 个轮次");
        }
        return reports.listCards(userId, conversationId, filter).stream().map(ReportStore::reference).toList();
    }

    /** 同时校验当前用户和有效会话，避免通过报告 ID 读取其他会话。 */
    public Detail detail(long conversationId, long userId, String reportId) {
        if (reportId == null || reportId.isBlank() || reportId.length() > 64) {
            throw new IllegalArgumentException("报告 ID 无效");
        }
        AiReportMapper.DetailRow row = reports.getOwned(userId, conversationId, reportId);
        if (row == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "报告不存在");
        }
        return new Detail(row.reportId(), row.requestId(), row.toolCallId(), row.title(), placeholder(row.title()),
                stripReferences(row.content()));
    }

    /**
     * 去掉参考列表与正文引用编号，只保留业务结论和依据说明。
     * 历史报告未在保存时处理过，读取时再处理一次，保证前端口径一致。
     *
     * @param content 审核通过的报告正文
     * @return 去掉参考资料后的正文；处理结果为空时保留原文
     */
    private static String stripReferences(String content) {
        Matcher heading = REFERENCE_HEADING.matcher(content);
        String body = heading.find() ? content.substring(0, heading.start()) : content;
        body = CITATION_MARK.matcher(body).replaceAll("");
        body = TRAILING_RULE.matcher(body).replaceAll("");
        body = body.stripTrailing();
        return body.isBlank() ? content : body;
    }

    private static Reference reference(AiReportMapper.CardRow row) {
        return new Reference(row.reportId(), row.requestId(), row.toolCallId(), row.title(), placeholder(row.title()));
    }

    private static String placeholder(String title) {
        return "[[REPORT:" + title + "]]";
    }

    /** 占位符必须可独立成行；将其中的方括号转换为全角符号。 */
    private static String normalizeTitle(String title) {
        String normalized = title == null ? "" : title.replaceAll("[\\p{Z}\\p{Cc}]+", " ").strip()
                .replace('[', '［').replace(']', '］');
        if (normalized.isEmpty()) {
            return "经营分析报告";
        }
        return truncate(normalized, MAX_TITLE_LENGTH);
    }

    /** MySQL 的字符上限按码点计算，保留完整的非 BMP 字符。 */
    private static String truncate(String value, int maxLength) {
        int length = value.codePointCount(0, value.length());
        if (length <= maxLength) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxLength)).stripTrailing();
    }
}
