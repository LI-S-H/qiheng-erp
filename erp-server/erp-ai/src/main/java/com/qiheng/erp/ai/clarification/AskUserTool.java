package com.qiheng.erp.ai.clarification;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * 用户追问工具：只声明参数结构，实际确认由图级 HITL 节点接管。
 *
 * <p>模型调用后，图在工具执行前中断并保存检查点；用户答案写回工具结果后恢复运行。
 * 因此本工具方法正常路径不会执行。</p>
 *
 * @author Li
 * @since 2026-10-27
 */
public final class AskUserTool {

    /** 工具名称，供 HITL 配置与检查点校验共用。 */
    public static final String TOOL_NAME = "ask_user";

    /** 决定用户对当前问题的回答方式。 */
    public enum QuestionType { SINGLE_CHOICE, MULTIPLE_CHOICE, TEXT }

    public record Question(
            @JsonProperty(required = true) @JsonPropertyDescription("必填，本轮所有问题内唯一且非空的标识，用于将用户回答对应到问题，例如 warehouse_scope") String id,
            @JsonProperty(required = true) @JsonPropertyDescription("必填，向用户直接展示的完整问题，清楚说明需要补充的对象和范围，不要要求重复已有条件。涉及相对时间时说明计算基准，不同基准需清晰区分") String question,
            @JsonProperty(required = true) @JsonPropertyDescription("必填，SINGLE_CHOICE 表示单选，MULTIPLE_CHOICE 表示可选多个，TEXT 表示自由填写；按实际问题选择") QuestionType type,
            @JsonProperty(required = true) @JsonPropertyDescription("必填字符串数组，每个字符串是一项。单选示例：[\"按商品查看\", \"按仓库查看\"]，用户选一项；多选示例：[\"库存数量\", \"库存金额\"]，用户可选多项。TEXT 使用空数组 []。候选项应有依据且不重复，不要编造业务对象；无法确认候选时使用 TEXT。时间选项应说明参照点及已知具体日期，不同基准不要使用相同的笼统名称") List<String> options) {
    }

    @Tool(name = AskUserTool.TOOL_NAME, description = "当完成当前任务需要用户补充条件，或描述不够清楚、需要确认目标或范围时，向用户提问并等待回答。"
            + "只问真正阻塞任务的问题；已回答或上下文中已确认的条件不要重复询问。"
            + "参数会先经过格式校验，确认卡片由前端展示，用户回答后继续执行。")
    public String ask(@ToolParam(description = "必填问题数组；每题包含唯一 id、面向用户的完整问题、回答类型和选项，具体规则见字段说明") List<Question> questions) {
        // HITL 在工具执行前中断，正常不会进入方法体；保留明确提示便于排查配置问题。
        return "追问已交由人工确认节点处理。";
    }
}