package com.qiheng.erp.ai.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/** 本次报告工具的进度发送器，发送失败不能影响报告执行。 */
public final class ReportProgress {
    public static final String METADATA_KEY = "reportProgress";
    private static final Logger log = LoggerFactory.getLogger(ReportProgress.class);
    private final String toolCallId;
    private final String taskId;
    private final BiConsumer<String, Map<String, Object>> publisher;
    private long sequence;

    public ReportProgress(String toolCallId, BiConsumer<String, Map<String, Object>> publisher) {
        if (toolCallId == null || toolCallId.isBlank() || publisher == null) {
            throw new IllegalArgumentException("报告进度缺少工具调用标识或事件发送器");
        }
        this.toolCallId = toolCallId;
        this.taskId = UUID.randomUUID().toString();
        this.publisher = publisher;
    }

    /** 报告记录关联本次工具调用，用于事件和历史回看。 */
    public String toolCallId() {
        return toolCallId;
    }

    /** 一次报告任务的内部编号，与模型提供的工具调用编号分开。 */
    public String taskId() {
        return taskId;
    }

    /** 按实际阶段发送短文本，不发送报告正文和工具数据。 */
    public synchronized void stage(String phase, String text) {
        try {
            publisher.accept("report_progress", Map.of("toolCallId", toolCallId, "taskId", taskId,
                    "phase", phase, "text", text, "stepSeq", ++sequence));
        } catch (RuntimeException error) {
            log.warn("发送报告进度失败 phase={}", phase, error);
        }
    }

    /** 服务端保存成功后发送报告卡片信息。 */
    public synchronized void ready(Map<String, Object> card) {
        var data = new LinkedHashMap<String, Object>(card);
        data.put("toolCallId", toolCallId);
        data.put("taskId", taskId);
        data.put("stepSeq", ++sequence);
        try {
            publisher.accept("report_ready", data);
        } catch (RuntimeException error) {
            log.warn("发送报告交付事件失败", error);
        }
    }
}
