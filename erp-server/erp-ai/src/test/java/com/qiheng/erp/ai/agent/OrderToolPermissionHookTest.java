package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.returnorder.domain.vo.ReturnOrderDetailVo;
import com.qiheng.erp.returnorder.service.IReturnOrderService;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 订单工具 HITL 前权限校验。
 *
 * @author Li
 * @since 2026-11-26
 */
class OrderToolPermissionHookTest {

    private static final long USER_ID = 1L;
    private final ObjectMapper mapper = new ObjectMapper();
    private final LoginUserService users = mock(LoginUserService.class);
    private final IReturnOrderService returnOrders = mock(IReturnOrderService.class);
    private final OrderToolPermissionHook hook = new OrderToolPermissionHook(users, returnOrders);

    /** 无权限只补 forbidden 工具结果并回到模型，不跳 END、不写最终回复。 */
    @Test
    void deniesSalesCreateWithoutPermissionAndReturnsToModel() throws Exception {
        loginWith(List.of("sales:manage"));

        var result = run("prepare_sales_order_draft", "{}");

        assertThat(result).doesNotContainKey("jump_to");
        var messages = (List<?>) result.get("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst()).isInstanceOf(ToolResponseMessage.class);
        var body = mapper.readTree(((ToolResponseMessage) messages.getFirst())
                .getResponses().getFirst().responseData());
        assertThat(body.path("status").asText()).isEqualTo("forbidden");
        assertThat(body.has("requiredPermission")).isFalse();
        assertThat(body.path("message").asText()).contains("业务权限").doesNotContain("sales:create");
        assertThat(body.path("message").asText()).contains("请告知用户");
    }

    /** 有 sales:create 时销售创建放行。 */
    @Test
    void allowsSalesCreateWithPermission() {
        loginWith(List.of("sales:create"));

        assertThat(run("prepare_sales_order_draft", "{}")).isEmpty();
    }

    /** 超管通配权限放行全部订单工具。 */
    @Test
    void allowsWildcardPermission() {
        loginWith(List.of("*"));

        assertThat(run("cancel_sales_order", "{\"request\":{\"salesOrderId\":\"9\"}}")).isEmpty();
        assertThat(run("submit_purchase_order", "{}")).isEmpty();
    }

    /** 销售提交需要 sales:manage，不能被 sales:create 混用。 */
    @Test
    void deniesSalesSubmitWithCreateOnly() throws Exception {
        loginWith(List.of("sales:create"));

        var result = run("submit_sales_order", "{}");

        assertThat(result).doesNotContainKey("jump_to");
        var response = (ToolResponseMessage) ((List<?>) result.get("messages")).getFirst();
        var body = mapper.readTree(response.getResponses().getFirst().responseData());
        assertThat(body.path("status").asText()).isEqualTo("forbidden");
        assertThat(body.path("message").asText()).contains("业务权限");
        assertThat(body.path("message").asText()).doesNotContain("sales:manage");
    }

    /** 退货草稿按 returnType 选择销售或采购创建权限。 */
    @Test
    void returnDraftUsesReturnTypePermission() {
        loginWith(List.of("sales:create"));

        assertThat(run("prepare_return_order_draft", "{\"request\":{\"returnType\":\"SALES_RETURN\"}}")).isEmpty();
        assertThat(run("prepare_return_order_draft", "{\"request\":{\"returnType\":\"PURCHASE_RETURN\"}}"))
                .containsKey("messages");
    }

    /** 退货提交按退货单方向要求对应模块的管理权限。 */
    @Test
    void returnActionUsesOrderDirectionPermission() {
        loginWith(List.of("sales:manage"));
        var detail = new ReturnOrderDetailVo();
        detail.setReturnType("SALES_RETURN");
        when(returnOrders.getDetail(8L)).thenReturn(detail);

        assertThat(run("submit_return_order", "{\"request\":{\"returnOrderId\":\"8\"}}")).isEmpty();

        loginWith(List.of("purchase:manage"));
        assertThat(run("cancel_return_order", "{\"request\":{\"returnOrderId\":\"8\"}}"))
                .containsKey("messages");
    }

    /** 退货方向无法确认时不做权限拦截，交给后续回执和前端校验。 */
    @Test
    void unresolvedReturnDirectionSkipsPermissionCheck() {
        loginWith(List.of("sales:manage"));
        when(returnOrders.getDetail(9L)).thenReturn(new ReturnOrderDetailVo());

        assertThat(run("submit_return_order", "{\"request\":{\"returnOrderId\":\"9\"}}")).isEmpty();
    }

    /** 同一轮有一个无权限调用时，整轮订单工具都不执行。 */
    @Test
    void batchOrderCallsAreNotExecutedWhenOneIsDenied() throws Exception {
        loginWith(List.of("sales:manage"));
        var allowed = new AssistantMessage.ToolCall("call-1", "function", "cancel_sales_order",
                "{\"request\":{\"salesOrderId\":\"9\"}}");
        var denied = new AssistantMessage.ToolCall("call-2", "function", "prepare_sales_order_draft", "{}");
        var message = AssistantMessage.builder().content("批量").toolCalls(List.of(allowed, denied)).build();
        var state = new OverAllState(Map.of("messages", List.of(message)));

        var result = hook.afterModel(state, config()).join();

        var response = (ToolResponseMessage) ((List<?>) result.get("messages")).getFirst();
        assertThat(response.getResponses()).hasSize(2);
        for (int index = 0; index < 2; index++) {
            var body = mapper.readTree(response.getResponses().get(index).responseData());
            assertThat(body.path("status").asText()).isEqualTo("forbidden");
            assertThat(body.path("message").asText()).contains("业务权限");
        }
    }

    /** 非订单工具调用不触发权限校验。 */
    @Test
    void ignoresUnrelatedToolCall() {
        loginWith(List.of());

        assertThat(run("query_sql_data", "{}")).isEmpty();
    }

    private void loginWith(List<String> permissions) {
        var user = new LoginUser();
        user.setUserId(USER_ID);
        user.setPermissionCodes(permissions);
        when(users.findByUserId(USER_ID)).thenReturn(user);
    }

    private RunnableConfig config() {
        return RunnableConfig.builder()
                .threadId("order-permission-test")
                .addMetadata(TrustedToolContextInterceptor.TRUSTED_CONTEXT,
                        new AgentToolContext(USER_ID, 2L, "2026-11-26"))
                .build();
    }

    private Map<String, Object> run(String toolName, String arguments) {
        var call = new AssistantMessage.ToolCall("call-1", "function", toolName, arguments);
        var message = AssistantMessage.builder().content("调用工具").toolCalls(List.of(call)).build();
        var state = new OverAllState(Map.of("messages", List.of(message)));
        return hook.afterModel(state, config()).join();
    }
}