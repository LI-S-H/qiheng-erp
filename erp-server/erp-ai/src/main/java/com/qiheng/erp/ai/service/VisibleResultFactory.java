package com.qiheng.erp.ai.service;

import com.qiheng.erp.ai.constant.RunStatuses;
import com.qiheng.erp.ai.domain.dto.needs.ClarificationQuestion;
import com.qiheng.erp.ai.domain.dto.needs.PlannerOutput;
import com.qiheng.erp.ai.domain.vo.VisibleMessageContent;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户可见消息展示内容（VisibleMessageContent）的唯一构造点。
 *
 * <p>发布拍落库（NeedCheckNode / NeedReviewNode）与事件投影必须产出形状一致的消息，
 * 协议形状只在本类维护：字段增删、文案调整只改这里。</p>
 *
 * @author Li
 * @since 2026-10-17
 */
@Component
public class VisibleResultFactory {

    private static final String PLAN_INCOMPLETE_TEXT = "本轮规划未完成，请调整需求后重新发起。";

    /**
     * 已通过校验的需求数组消息：type=NEEDS。
     *
     * @param needs    通过审阅的需求数组
     * @param threadId 检查点线程标识，由 ConversationThreads 统一拼装
     * @return 需求展示内容
     */
    public VisibleMessageContent acceptedNeeds(List<Map<String, Object>> needs, String threadId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", describe(needs));
        data.put("needs", needs);
        data.put("threadId", threadId);
        return new VisibleMessageContent("NEEDS", data);
    }

    /**
     * 澄清问题消息：type=CLARIFICATION。
     *
     * <p>pauseId 由暂停拍生成，是页面恢复与恢复校验的功能必需字段；
     * 落库消息与 clarification_required 事件投影共用本方法构造同一份内容。</p>
     *
     * @param output   缺少信息的 planner 输出
     * @param threadId 检查点线程标识
     * @param pauseId  本次暂停标识
     * @return 澄清展示内容
     */
    public VisibleMessageContent clarification(PlannerOutput output, String threadId, String pauseId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", describeClarification(output));
        data.put("threadId", threadId);
        data.put("pauseId", pauseId);
        data.put("questions", output.questions());
        return new VisibleMessageContent("CLARIFICATION", data);
    }

    /**
     * 格式重试耗尽消息：type=ERROR。
     *
     * <p>status 标记是本条提示与运行失败提示的区分依据：读取路径据此还原
     * FORMAT_RETRY_EXHAUSTED，不依赖检查点编号是否存在。</p>
     *
     * @param threadId 检查点线程标识
     * @return 未完成提示内容
     */
    public VisibleMessageContent planIncomplete(String threadId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", PLAN_INCOMPLETE_TEXT);
        data.put("threadId", threadId);
        data.put("status", RunStatuses.FORMAT_RETRY_EXHAUSTED);
        return new VisibleMessageContent("ERROR", data);
    }

    /**
     * 把需求数组转成用户可读文本，完整需求单独放在展示协议 data.needs 中。
     *
     * @param needs 需求数组
     * @return 需求摘要文本
     */
    private String describe(List<Map<String, Object>> needs) {
        StringBuilder sb = new StringBuilder();
        sb.append("已拆解出 ").append(needs.size()).append(" 条需求：");
        for (Map<String, Object> need : needs) {
            sb.append("\n- ").append(need.get("id")).append(" ").append(need.get("need"));
        }
        return sb.toString();
    }

    /**
     * 把澄清问题转成用户可读文本。
     */
    private String describeClarification(PlannerOutput output) {
        StringBuilder sb = new StringBuilder("需要补充信息：");
        for (ClarificationQuestion question : output.questions()) {
            sb.append("\n- ").append(question.question());
        }
        return sb.toString();
    }
}
