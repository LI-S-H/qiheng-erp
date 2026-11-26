package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.sql.scope.PermissionCodes;
import com.qiheng.erp.returnorder.domain.vo.ReturnOrderDetailVo;
import com.qiheng.erp.returnorder.service.IReturnOrderService;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * HITL 之前的订单工具业务权限校验。
 *
 * <p>本 Hook 的 order 小于追问校验、大于 HITL，按 AFTER_MODEL 倒序执行，
 * 因此在弹出人工确认卡之前拦住没有业务权限的工具调用。无权限时只补一条
 * forbidden 工具结果并回到模型节点，不终止整轮运行，也不替模型生成最终回复。</p>
 *
 * @author Li
 * @since 2026-11-26
 */
@HookPositions({HookPosition.AFTER_MODEL})
public final class OrderToolPermissionHook extends ModelHook {

    private static final Logger log = LoggerFactory.getLogger(OrderToolPermissionHook.class);

    private static final String PURCHASE_CREATE = "purchase:create";
    private static final String PURCHASE_MANAGE = "purchase:manage";
    private static final String SALES_CREATE = "sales:create";
    private static final String SALES_MANAGE = "sales:manage";

    /** 固定工具名到业务权限码的映射。 */
    private static final Map<String, String> FIXED_PERMISSIONS = Map.ofEntries(
            Map.entry("prepare_purchase_order_draft", PURCHASE_CREATE),
            Map.entry("update_purchase_order_draft", PURCHASE_CREATE),
            Map.entry("submit_purchase_order", PURCHASE_MANAGE),
            Map.entry("cancel_purchase_order", PURCHASE_MANAGE),
            Map.entry("prepare_sales_order_draft", SALES_CREATE),
            Map.entry("update_sales_order_draft", SALES_CREATE),
            Map.entry("submit_sales_order", SALES_MANAGE),
            Map.entry("cancel_sales_order", SALES_MANAGE));

    /** 退货草稿工具：按 returnType 选择创建权限。 */
    private static final Set<String> RETURN_DRAFT_TOOLS = Set.of(
            "prepare_return_order_draft", "update_return_order_draft");

    /** 退货动作工具：按退货单方向选择管理权限。 */
    private static final Set<String> RETURN_ACTION_TOOLS = Set.of(
            "submit_return_order", "cancel_return_order");

    private final LoginUserService users;
    private final IReturnOrderService returnOrders;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 绑定用户与退货单查询。
     *
     * @param users 登录用户查询
     * @param returnOrders 退货单只读查询，用于判断退货方向
     */
    public OrderToolPermissionHook(LoginUserService users, IReturnOrderService returnOrders) {
        this.users = users;
        this.returnOrders = returnOrders;
    }

    /**
     * 在模型产出工具调用后、HITL 暂停前校验业务权限。
     *
     * @param state 图状态
     * @param config 运行配置
     * @return 无权限时写入 forbidden 工具结果，由模型继续处理
     */
    @Override
    public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
        List<AssistantMessage.ToolCall> allCalls = findToolCalls(state);
        List<AssistantMessage.ToolCall> orderCalls = allCalls.stream()
                .filter(this::isOrderTool)
                .toList();
        if (orderCalls.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        List<String> permissions = currentPermissions(config);
        boolean allowed = permissions.contains("*");
        Map<String, String> requiredByCall = new LinkedHashMap<>();
        for (AssistantMessage.ToolCall call : orderCalls) {
            String required = requiredPermission(call);
            if (required != null && !allowed && !permissions.contains(required)) {
                requiredByCall.put(call.id(), required);
            }
        }
        if (requiredByCall.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        requiredByCall.forEach((callId, permission) ->
                log.info("订单工具权限不足 callId={} permission={}", callId, permission));

        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall call : allCalls) {
            responses.add(new ToolResponseMessage.ToolResponse(
                    call.id(), call.name(), forbiddenJson()));
        }
        ToolResponseMessage message = ToolResponseMessage.builder().responses(responses).build();
        return CompletableFuture.completedFuture(Map.of("messages", List.of(message)));
    }

    /** 权限校验先于 HITL、晚于追问校验执行。 */
    @Override
    public int getOrder() {
        return 90;
    }

    @Override
    public String getName() {
        return "order_tool_permission";
    }

    /** 找到最后一条 AssistantMessage 里的全部工具调用。 */
    @SuppressWarnings("unchecked")
    private List<AssistantMessage.ToolCall> findToolCalls(OverAllState state) {
        Object raw = state.value("messages").orElse(null);
        if (!(raw instanceof List<?> messages) || messages.isEmpty()
                || !(messages.getLast() instanceof AssistantMessage assistant)) {
            return List.of();
        }
        return assistant.getToolCalls();
    }

    /** 是否为本 Hook 需要校验的订单工具。 */
    private boolean isOrderTool(AssistantMessage.ToolCall call) {
        return FIXED_PERMISSIONS.containsKey(call.name())
                || RETURN_DRAFT_TOOLS.contains(call.name())
                || RETURN_ACTION_TOOLS.contains(call.name());
    }

    /** 解析工具对应的业务权限；退货方向无法确认时返回 null。 */
    private String requiredPermission(AssistantMessage.ToolCall call) {
        String fixed = FIXED_PERMISSIONS.get(call.name());
        if (fixed != null) {
            return fixed;
        }
        if (RETURN_DRAFT_TOOLS.contains(call.name())) {
            String returnType = textArgument(call.arguments(), "returnType");
            return "PURCHASE_RETURN".equals(returnType) ? PURCHASE_CREATE
                    : "SALES_RETURN".equals(returnType) ? SALES_CREATE : null;
        }
        if (RETURN_ACTION_TOOLS.contains(call.name())) {
            return returnActionPermission(call);
        }
        return null;
    }

    /** 退货提交/取消先读单据方向，再要求对应模块的管理权限。 */
    private String returnActionPermission(AssistantMessage.ToolCall call) {
        String idText = textArgument(call.arguments(), "returnOrderId");
        if (idText == null || idText.isBlank()) {
            return null;
        }
        try {
            ReturnOrderDetailVo detail = returnOrders.getDetail(Long.parseLong(idText.trim()));
            return "PURCHASE_RETURN".equals(detail.getReturnType()) ? PURCHASE_MANAGE
                    : "SALES_RETURN".equals(detail.getReturnType()) ? SALES_MANAGE : null;
        } catch (RuntimeException error) {
            return null;
        }
    }

    /** 从工具参数中读取字段，兼容 request 包装层。 */
    private String textArgument(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode node = root != null && root.path("request").isObject() ? root.path("request") : root;
            JsonNode value = node == null ? null : node.get(field);
            return value == null || value.isNull() ? null : value.asText();
        } catch (Exception error) {
            return null;
        }
    }

    /** 从运行配置读取并解析当前用户权限。 */
    private List<String> currentPermissions(RunnableConfig config) {
        Object raw = config.metadata(TrustedToolContextInterceptor.TRUSTED_CONTEXT).orElse(null);
        if (!(raw instanceof AgentToolContext context)) {
            return List.of();
        }
        LoginUser user = users.findByUserId(context.userId());
        return PermissionCodes.of(user);
    }

    /** 缺少业务权限：不暴露内部权限码，只提示模型告知用户或改用其他操作。 */
    private String forbiddenJson() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "forbidden");
        body.put("message", "当前用户缺少该操作的业务权限，本次调用未执行。"
                + "请告知用户联系管理员授权，或改用用户有权限的其他操作。");
        return json(body);
    }

    private String json(Map<String, Object> body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception error) {
            return "{\"status\":\"forbidden\",\"message\":\"当前用户缺少该操作的业务权限\"}";
        }
    }
}