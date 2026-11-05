package com.qiheng.erp.ai.clarification;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import java.util.List;
import java.util.function.Consumer;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.qiheng.erp.ai.clarification.ClarificationService;
import com.qiheng.erp.ai.sql.tool.SqlQueryTool;
import com.qiheng.erp.ai.sql.tool.SqlToolContext;
import org.springframework.ai.chat.model.ToolContext;

/** 用户追问工具：发出问题并等待服务端接收用户回答。 */
public final class AskUserTool {
    private final ClarificationService service;
    public AskUserTool(ClarificationService service) { this.service = service; }
    /** 决定用户对当前问题的回答方式。 */
    public enum QuestionType { SINGLE_CHOICE, MULTIPLE_CHOICE, TEXT }

    public record Question(
            @JsonProperty(required = true) @JsonPropertyDescription("必填，本轮所有问题内唯一且非空的标识，用于将用户回答对应到问题，例如 warehouse_scope。") String id,
            @JsonProperty(required = true) @JsonPropertyDescription("必填，向用户直接展示的完整问题，清楚说明需要补充的对象和范围，不要要求重复已有条件。涉及相对时间时说明计算基准，不同基准需清晰区分。") String question,
            @JsonProperty(required = true) @JsonPropertyDescription("必填，SINGLE_CHOICE 表示单选，MULTIPLE_CHOICE 表示可选多个，TEXT 表示自由填写；按实际问题选择。") QuestionType type,
            @JsonProperty(required = true) @JsonPropertyDescription("必填字符串数组，每个字符串是一项。单选示例：[\"按商品查看\", \"按仓库查看\"]，用户选一项；多选示例：[\"库存数量\", \"库存金额\"]，用户可选多项。TEXT 使用空数组 []。候选项应有依据且不重复，不要编造业务对象；无法确认候选时使用 TEXT。时间选项应说明参照点及已知具体日期，不同基准不要使用相同的笼统名称。") List<String> options) {}

    @Tool(name = "ask_user", description = "当完成当前任务需要用户补充条件，或用户描述不够清楚、需要确认具体目标或范围时，向用户提出问题。工具会展示问题并等待回答，返回结构化答案，供你结合原任务继续规划。")
    public String ask(@ToolParam(description = "必填的非空问题数组。每题填写唯一 id、面向用户的完整 question、回答类型 type 和 options：SINGLE_CHOICE 供用户选一项，MULTIPLE_CHOICE 供用户选择多项，二者 options 为有依据且不重复的候选字符串数组；TEXT 为自由输入，options 填空数组。范围选项可使用全部或指定范围，不要编造具体业务对象；用户也可补充自定义内容。") List<Question> questions, ToolContext context) throws Exception {
        var identity = ToolContextHelper.getMetadata(context, SqlQueryTool.CONTEXT_KEY, SqlToolContext.class).orElseThrow();
        var events = (Consumer<NodeOutput>) context.getContext().get(ClarificationService.EVENT_KEY);
        return service.ask(identity.userId(), identity.conversationId(), questions, events);
    }
}
