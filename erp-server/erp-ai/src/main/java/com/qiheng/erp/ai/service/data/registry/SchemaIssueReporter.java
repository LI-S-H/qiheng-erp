package com.qiheng.erp.ai.service.data.registry;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.qiheng.erp.common.constant.SystemExceptionConstants;
import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import com.qiheng.erp.common.mq.SystemExceptionRecordMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 元数据异常上报：把「表间关系指向不存在的列」这类不会自愈的问题送到系统异常通道。
 *
 * <p>走系统异常通道而不是只打日志，是因为模型会照着失效关系生成必然失败的 SQL，
 * 而且问题不会自愈——必须人工修正 {@code ai_data_table_relation}。上报后工作台会出现
 * 一条 SYSTEM_EXCEPTION 待办，运维看得见。</p>
 *
 * <p>去重：只在<b>新出现</b>的失效关系时上报。一直坏的不重复刷屏；
 * 人工修好后不再为「已恢复」发一条——工作台那条待办由人工处理掉即可。
 * 进程重启会重新上报一次：问题还在，工作台就该有这条待办。</p>
 *
 * @author Li
 * @since 2026-10-21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaIssueReporter {

    /** 来源标识：一次同步一条记录，具体关系列在错误信息里。 */
    private static final String SOURCE_NO = "AI_DATA_SCHEMA_SYNC";

    /**
     * 错误信息与摘要的长度上限。
     *
     * <p>对应 {@code system_exception} 表的 {@code error_message} 与 {@code detail_summary}
     * 两列都是 {@code VARCHAR(1000)}，而消费端不做截断——超长会直接 insert 失败，
     * 消息被反复重投。这里先收敛好，不把超长问题留给消费端。</p>
     */
    private static final int MAX_TEXT_LENGTH = 1000;

    private final SystemExceptionMqPublisher publisher;

    /** 本次运行已上报过的失效关系，集合没变就不重复上报。 */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    /**
     * 上报失效的表间关系。
     *
     * @param relations 失效关系标识，形如 {@code from_table.from_column=to_table.to_column}
     */
    public void reportInvalidRelations(List<String> relations) {
        if (relations == null || relations.isEmpty()) {
            return;
        }
        Set<String> fresh = new LinkedHashSet<>();
        for (String relation : relations) {
            if (reported.add(relation)) {
                fresh.add(relation);
            }
        }
        if (fresh.isEmpty()) {
            return;
        }
        String detail = String.join("；", fresh);
        SystemExceptionRecordMessage message = new SystemExceptionRecordMessage();
        message.setEventId(IdWorker.getIdStr());
        message.setExceptionType(SystemExceptionConstants.TYPE_DATA_SCHEMA_INVALID);
        message.setSourceModule(SystemExceptionConstants.MODULE_AI);
        message.setSourceNo(SOURCE_NO);
        message.setSeverity(SystemExceptionConstants.SEVERITY_HIGH);
        message.setErrorCode(SystemExceptionConstants.CODE_DATA_RELATION_INVALID);
        message.setErrorMessage(truncate("元数据表间关系指向不存在的列：" + detail, fresh.size()));
        message.setDetailSummary(truncate("AI 数据表元数据同步发现 " + fresh.size()
                + " 条表间关系指向库中不存在的列，模型按这些关系生成 SQL 会必然失败", fresh.size()));
        message.setResolveHint("到 ai_data_table_relation 修正关系登记；若列确实存在，"
                + "检查该列是否已在 ai_data_table_column 登记");
        message.setOccurredAt(System.currentTimeMillis());
        publisher.publish(message);
    }

    /**
     * 收敛文本长度；被截断时补一句「等 N 条」，让看板上知道后面还有。
     */
    private String truncate(String text, int total) {
        if (text.length() <= MAX_TEXT_LENGTH) {
            return text;
        }
        String suffix = "（共 " + total + " 条，完整清单见应用日志）";
        return text.substring(0, MAX_TEXT_LENGTH - suffix.length()) + suffix;
    }
}
