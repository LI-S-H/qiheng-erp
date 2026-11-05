package com.qiheng.erp.ai.sql.execution;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.qiheng.erp.common.constant.SystemExceptionConstants;
import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import com.qiheng.erp.common.mq.SystemExceptionRecordMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SQL 守卫缺口上报：守卫放过了、但被 Druid wall 拦下的 SQL，送到系统异常通道。
 *
 * <p><b>为什么这条值得上报</b>：{@code TIMEOUT} / {@code SYNTAX} 是模型写错了，
 * 重试或停止就行；{@code WALL_REJECTED} 是<b>我们的守卫漏了</b>——一条本该在应用层
 * 被拒绝的 SQL 跑到了连接层，靠 wall 兜住。这是安全防线的缺口，必须有人补规则，
 * 只躺在日志里没人会去翻。</p>
 *
 * <p><b>为什么不在守卫里报</b>：守卫是纯函数、不依赖 Spring，要能脱离环境单测。
 * "守卫放过但墙拦了"只有执行器知道，所以上报是执行器的运行时职责。</p>
 *
 * <p><b>去重</b>：同一 {@code sqlHash} 十分钟内只报一次。模型重试循环可能同一条 SQL
 * 反复撞墙，不节流会把工作台刷爆；十分钟的窗口既压住刷屏，又能在问题持续时提醒一次。</p>
 *
 * <p><b>失败不影响取数</b>：上报是 best-effort，任何异常只记日志——
 * 报警失败绝不能把本来能返回的查询结果搞没。</p>
 *
 * @author Li
 * @since 2026-10-26
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlGuardGapReporter {

    /** 来源标识：工作台按它定位到守卫这一层。 */
    private static final String SOURCE_NO = "AI_SQL_GUARD";

    /**
     * 文本长度上限。
     *
     * <p>{@code system_exception} 的 {@code error_message} 与 {@code detail_summary}
     * 都是 {@code VARCHAR(1000)}，消费端不做截断——超长会 insert 失败、消息反复重投。</p>
     */
    private static final int MAX_TEXT_LENGTH = 1000;

    /** 同一 SQL 的上报窗口。 */
    private static final long DEDUP_WINDOW_MILLIS = 10 * 60 * 1000L;

    /** 去重表上限，超过就清过期的；全是新的就整体清空，避免无界增长。 */
    private static final int MAX_ENTRIES = 500;

    private final SystemExceptionMqPublisher publisher;

    /** sqlHash → 上次上报时间。 */
    private final Map<String, Long> reportedAt = new ConcurrentHashMap<>();

    /**
     * 上报一次守卫缺口。
     *
     * @param runId       本轮运行标识
     * @param stepId      当前步骤标识
     * @param sqlHash     原始 SQL 的哈希（去重键）
     * @param wallMessage wall 过滤器给的原始消息（含 SQL 原文，放 errorMessage 供排查）
     */
    public void reportWallRejected(String runId, String stepId, String sqlHash, String wallMessage) {
        try {
            long now = System.currentTimeMillis();
            Long previous = reportedAt.get(sqlHash);
            if (previous != null && now - previous < DEDUP_WINDOW_MILLIS) {
                return;
            }
            reportedAt.put(sqlHash, now);
            evictIfNeeded(now);

            String shortHash = sqlHash == null || sqlHash.length() <= 8
                    ? String.valueOf(sqlHash) : sqlHash.substring(0, 8);
            SystemExceptionRecordMessage message = new SystemExceptionRecordMessage();
            message.setEventId(IdWorker.getIdStr());
            message.setExceptionType(SystemExceptionConstants.TYPE_SQL_GUARD_GAP);
            message.setSourceModule(SystemExceptionConstants.MODULE_AI);
            message.setSourceNo(SOURCE_NO + "/" + runId);
            message.setSeverity(SystemExceptionConstants.SEVERITY_HIGH);
            message.setErrorCode(SystemExceptionConstants.CODE_SQL_GUARD_GAP);
            // 具体 SQL 放这里：工作台展开可见，用于定位是哪条语句绕过了守卫
            message.setErrorMessage(truncate("守卫放行但被 wall 拦截：" + wallMessage));
            // 摘要只放标识，不放 SQL 原文
            message.setDetailSummary(truncate("AI SQL 守卫可能有缺口：runId=" + runId
                    + " stepId=" + stepId + " sqlHash=" + shortHash));
            message.setResolveHint("检查 DruidSqlGuard 的规则，补上被 wall 拦截的形态；"
                    + "必要时调整 wall 配置");
            message.setOccurredAt(now);
            publisher.publish(message);
        } catch (RuntimeException error) {
            log.warn("SQL 守卫缺口上报失败 runId={} stepId={}", runId, stepId, error);
        }
    }

    /**
     * 去重表过大时清理过期项；仍然过大就整体清空。
     */
    private void evictIfNeeded(long now) {
        if (reportedAt.size() <= MAX_ENTRIES) {
            return;
        }
        reportedAt.entrySet().removeIf(entry -> now - entry.getValue() > DEDUP_WINDOW_MILLIS);
        if (reportedAt.size() > MAX_ENTRIES) {
            reportedAt.clear();
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_TEXT_LENGTH
                ? text : text.substring(0, MAX_TEXT_LENGTH - 1) + "…";
    }
}