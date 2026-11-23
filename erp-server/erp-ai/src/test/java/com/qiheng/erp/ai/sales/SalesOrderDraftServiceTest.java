package com.qiheng.erp.ai.sales;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 销售草稿等待通道：参数校验、回执内容与超时行为。 */
class SalesOrderDraftServiceTest {

    private final AiConversationMapper conversations = mock(AiConversationMapper.class);
    private final LoginUserService users = mock(LoginUserService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void returnsEditedReceiptWithChangedFieldsAndUserNote() throws Exception {
        var service = service(Duration.ofSeconds(3));
        var requestId = new CompletableFuture<String>();
        var output = CompletableFuture.supplyAsync(() -> {
            try {
                return service.request(SalesOrderDraftAction.CREATE, 1, 2, "run-1", draft(new BigDecimal("100")),
                        value -> requestId.complete((String) value.state().data().get("draftRequestId")));
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        var id = requestId.get(2, TimeUnit.SECONDS);
        service.resolveForUser(1, 2, id, new SalesOrderDraftReceipt(
                SalesOrderDraftReceipt.STATUS_EDITED, "SO2026112300001", "2124530107115659266", null,
                null, Map.of(
                        "customerId", "2020000000000000001",
                        "warehouseId", "1930000000000000001",
                        "expectedDeliveryDate", "2026-12-08",
                        "remark", "首批试单",
                        "items", List.of(Map.of(
                                "productId", "1940000000000000001",
                                "quantity", 80,
                                "unitPrice", "9.50",
                                "remark", ""))),
                "客户要求先小批量试单", null, null));
        var text = output.get(2, TimeUnit.SECONDS);
        assertThat(text).contains("用户修改参数后确认创建", "SO2026112300001", "最终参数：", "修改的字段：");
        assertThat(text).contains("items[0].quantity", "items[0].unitPrice", "expectedDeliveryDate", "remark");
        assertThat(text).contains("用户说明：客户要求先小批量试单");
        assertThat(text).doesNotContain("items[0].productId");
    }

    @Test
    void reportsCreatedOrderNumberInPlainText() throws Exception {
        var service = service(Duration.ofSeconds(3));
        var requestId = new CompletableFuture<String>();
        var output = CompletableFuture.supplyAsync(() -> {
            try {
                return service.request(SalesOrderDraftAction.CREATE, 1, 2, "run-1", draft(new BigDecimal("100")),
                        value -> requestId.complete((String) value.state().data().get("draftRequestId")));
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        var id = requestId.get(2, TimeUnit.SECONDS);
        service.resolveForUser(1, 2, id, new SalesOrderDraftReceipt(
                SalesOrderDraftReceipt.STATUS_CREATED, "SO2026112300001", "2124530107115659266", "DRAFT",
                null, null, null, null, null));
        var createdText = output.get(2, TimeUnit.SECONDS);
        assertThat(createdText).contains("用户确认创建", "SO2026112300001", "已生成草稿");
        assertThat(createdText).contains("2124530107115659266");
    }

    @Test
    void reportsFailedReasonWithoutChangeSummary() throws Exception {
        var service = service(Duration.ofSeconds(3));
        var requestId = new CompletableFuture<String>();
        var output = CompletableFuture.supplyAsync(() -> {
            try {
                return service.request(SalesOrderDraftAction.CREATE, 1, 2, "run-1", draft(new BigDecimal("100")),
                        value -> requestId.complete((String) value.state().data().get("draftRequestId")));
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        var id = requestId.get(2, TimeUnit.SECONDS);
        service.resolveForUser(1, 2, id, new SalesOrderDraftReceipt(
                SalesOrderDraftReceipt.STATUS_FAILED, null, null, null, "客户已停用，不能创建销售单",
                Map.of(
                        "customerId", "2020000000000000001",
                        "warehouseId", "1930000000000000001",
                        "items", List.of(Map.of(
                                "productId", "1940000000000000001",
                                "quantity", 80,
                                "unitPrice", 3,
                                "remark", ""))),
                null, null, null));
        var text = output.get(2, TimeUnit.SECONDS);
        assertThat(text).contains("创建未通过", "客户已停用，不能创建销售单", "被拒参数：");
        assertThat(text).doesNotContain("改动");
    }

    @Test
    void reportsRejectedReasonsAndSuggestion() throws Exception {
        var service = service(Duration.ofSeconds(3));
        var requestId = new CompletableFuture<String>();
        var output = CompletableFuture.supplyAsync(() -> {
            try {
                return service.request(SalesOrderDraftAction.CREATE, 1, 2, "run-1", draft(new BigDecimal("100")),
                        value -> requestId.complete((String) value.state().data().get("draftRequestId")));
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        var id = requestId.get(2, TimeUnit.SECONDS);
        service.resolveForUser(1, 2, id, new SalesOrderDraftReceipt(
                SalesOrderDraftReceipt.STATUS_REJECTED, null, null, null, null, null, null,
                List.of("价格不合适", "数量不合适"), "希望分两批发货"));
        assertThat(output.get(2, TimeUnit.SECONDS)).contains(
                "用户驳回了销售草稿，未生成销售单", "驳回原因：价格不合适、数量不合适", "补充要求：希望分两批发货");
    }

    @Test
    void rejectsRejectedReceiptWithoutReason() throws Exception {
        var service = service(Duration.ofSeconds(3));
        var requestId = new CompletableFuture<String>();
        CompletableFuture.supplyAsync(() -> {
            try {
                return service.request(SalesOrderDraftAction.CREATE, 1, 2, "run-1", draft(new BigDecimal("100")),
                        value -> requestId.complete((String) value.state().data().get("draftRequestId")));
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        var id = requestId.get(2, TimeUnit.SECONDS);
        assertThatThrownBy(() -> service.resolveForUser(1, 2, id, new SalesOrderDraftReceipt(
                SalesOrderDraftReceipt.STATUS_REJECTED, null, null, null, null, null, null, List.of(), " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("驳回原因");
    }

    @Test
    void validatesDraftBeforeWaitingForUser() throws Exception {
        var service = service(Duration.ofSeconds(3));
        var invalid = new SalesOrderDraftRequest(null, "2020000000000000001",
                "1930000000000000001", null, null, List.of());
        var result = service.request(SalesOrderDraftAction.CREATE, 1, 2, "run-1", invalid, value -> {
            throw new AssertionError("参数不合法时不应该发出确认事件");
        });
        assertThat(result).contains("items");
    }

    @Test
    void requiresUnitPriceForSalesLine() throws Exception {
        var service = service(Duration.ofSeconds(3));
        var missingPrice = new SalesOrderDraftRequest(null, "2020000000000000001",
                "1930000000000000001", null, null,
                List.of(new SalesOrderDraftRequest.Item("1940000000000000001",
                        new BigDecimal("10"), null, null)));
        var result = service.request(SalesOrderDraftAction.CREATE, 1, 2, "run-1", missingPrice, value -> {
            throw new AssertionError("缺少单价时不应该发出确认事件");
        });
        assertThat(result).contains("单价");
    }

    @Test
    void timeoutStopsWaiting() {
        var service = service(Duration.ofMillis(20));
        assertThatThrownBy(() -> service.request(SalesOrderDraftAction.CREATE, 1, 2, "run-1", draft(new BigDecimal("100")), value -> { }))
                .hasMessageContaining(SalesOrderDraftService.TIMEOUT_MARKER);
    }

    /** 构造通过归属与权限校验的服务实例。 */
    private SalesOrderDraftService service(Duration timeout) {
        when(conversations.selectOwned(2L, 1L)).thenReturn(new AiConversation());
        var user = new LoginUser();
        user.setPermissionCodes(List.of("ai:assistant:use"));
        when(users.findByUserId(1L)).thenReturn(user);
        return new SalesOrderDraftService(conversations, users,
                mock(com.qiheng.erp.ai.conversation.ConversationMessageStore.class), mapper, timeout);
    }

    /** 构造一份合法草稿。 */
    private SalesOrderDraftRequest draft(BigDecimal quantity) {
        return new SalesOrderDraftRequest(null, "2020000000000000001",
                "1930000000000000001", null, null,
                List.of(new SalesOrderDraftRequest.Item(
                        "1940000000000000001", quantity, new BigDecimal("10.00"), null)));
    }
}