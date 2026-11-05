package com.qiheng.erp.ai.clarification;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.sql.scope.PermissionCodes;
import com.qiheng.erp.ai.clarification.AskUserTool.Question;
import com.qiheng.erp.ai.clarification.AskUserTool.QuestionType;
import com.qiheng.erp.ai.clarification.ClarificationAnswer;
import com.qiheng.erp.security.service.LoginUserService;
import com.qiheng.erp.security.context.UserContext;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** 管理当前进程中等待用户回答的追问。 */
public final class ClarificationService {
    public static final String EVENT_KEY = "clarificationEvent";
    static final String TIMEOUT_MARKER = "ASK_USER_WAIT_TIMEOUT";
    private final ConcurrentMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final AiConversationMapper conversations;
    private final LoginUserService users;
    private final ObjectMapper mapper;
    private final Duration timeout;
    private record Pending(long userId, long conversationId, List<Question> questions,
                           CompletableFuture<Map<String, Object>> answer) {}

    public ClarificationService(AiConversationMapper conversations, LoginUserService users, ObjectMapper mapper) {
        this(conversations, users, mapper, Duration.ofMinutes(30));
    }
    public ClarificationService(AiConversationMapper conversations, LoginUserService users, ObjectMapper mapper, Duration timeout) {
        this.conversations = conversations;
        this.users = users;
        this.mapper = mapper;
        this.timeout = timeout;
    }

    /** 发送追问并等待另一个请求提交答案。 */
    public String ask(long userId, long conversationId, List<Question> questions, Consumer<NodeOutput> events) throws Exception {
        if (questions == null || questions.isEmpty()) {
            return "追问格式错误：questions 不能为空，请修正后重新调用。";
        }
        var ids = new HashSet<String>();
        for (var question : questions) {
            if (question == null || question.id() == null || question.id().isBlank()) {
                return "追问格式错误：问题 ID 不能为空。";
            }
            if (!ids.add(question.id())) {
                return "追问格式错误：问题 ID 重复：" + question.id();
            }
            if (question.question() == null || question.question().isBlank()) {
                return "追问格式错误：问题正文不能为空：" + question.id();
            }
            if (question.type() == null || question.options() == null
                    || (question.type() != QuestionType.TEXT && question.options().isEmpty())) {
                return "追问格式错误：请检查问题类型及选择题选项：" + question.id();
            }
            var options = new HashSet<String>();
            for (String option : question.options()) {
                if (option == null || option.isBlank() || !options.add(option)) {
                    return "追问格式错误：选项不能为空或重复：" + question.id();
                }
            }
        }
        String id = UUID.randomUUID().toString();
        var request = new Pending(userId, conversationId, List.copyOf(questions), new CompletableFuture<>());
        pending.put(id, request);
        try {
            var state = new OverAllState(Map.of("questionRequestId", id, "questions", questions));
            events.accept(NodeOutput.of("ask_user", "main_agent", state, null));
            return mapper.writeValueAsString(request.answer().get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            // 答复和超时竞争时只允许一个结果生效，避免接口误报提交成功。
            if (!request.answer().completeExceptionally(e)) {
                return mapper.writeValueAsString(request.answer().join());
            }
            throw new IllegalStateException(TIMEOUT_MARKER, e);
        } finally {
            pending.remove(id, request);
        }
    }

    /** 提交答案，不获取主任务持有的会话锁。 */
    public void answerForCurrentUser(long conversationId, String requestId, List<ClarificationAnswer> answers, String note) {
        answerForUser(UserContext.requireCurrentUser().getUserId(), conversationId, requestId, answers, note);
    }

    /** 仅供同包可信调用方及测试使用，不接受客户端传入身份。 */
    void answerForUser(long userId, long conversationId, String requestId, List<ClarificationAnswer> answers, String note) {
        if (conversations.selectOwned(conversationId, userId) == null) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
        var user = users.findByUserId(userId);
        var permissions = PermissionCodes.of(user);
        if (user == null || (!permissions.contains("*") && !permissions.contains("ai:assistant:use"))) {
            throw new IllegalStateException("当前用户无 AI 使用权限");
        }
        var request = pending.get(requestId);
        if (request == null || request.userId() != userId || request.conversationId() != conversationId) {
            throw new IllegalStateException("追问不存在或已结束");
        }
        if (answers == null || answers.size() != request.questions().size()) {
            throw new IllegalArgumentException("请回答所有追问");
        }
        var byId = new HashMap<String, ClarificationAnswer>();
        for (var answer : answers) {
            if (answer == null || answer.questionId() == null
                    || byId.putIfAbsent(answer.questionId(), answer) != null) {
                throw new IllegalArgumentException("回答与当前追问不匹配");
            }
        }
        for (var question : request.questions()) {
            var answer = byId.get(question.id());
            if (answer == null || answer.selectedOptions() == null
                    || (answer.selectedOptions().isEmpty()
                    && (answer.customText() == null || answer.customText().isBlank()))) {
                throw new IllegalArgumentException("请回答所有追问");
            }
            var selected = answer.selectedOptions();
            if ((question.type() == QuestionType.TEXT && !selected.isEmpty())
                    || (question.type() == QuestionType.SINGLE_CHOICE && selected.size() > 1)
                    || new HashSet<>(selected).size() != selected.size()
                    || !question.options().containsAll(selected)) {
                throw new IllegalArgumentException("回答选项不符合题目类型");
            }
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("answers", answers);
        if (note != null && !note.isBlank()) {
            result.put("note", note);
        }
        if (!request.answer().complete(result)) {
            throw new IllegalStateException("追问已回答");
        }
    }
}
