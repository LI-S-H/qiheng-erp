package com.qiheng.erp.ai.draft;

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

/** 采购草稿等待通道：参数校验、回执内容与超时行为。 */
class PurchaseDraftServiceTest {

    private final AiConversationMapper conversations = mock(AiConversationMapper.class);
    private final LoginUserService users = mock(LoginUserService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void returnsEditedReceiptWithChangedFieldsAndUserNote() throws Exception {
        var service = service(Duration.ofSeconds(3));
        var requestId = new CompletableFuture<String>();
        var output = CompletableFuture.supplyAsync(() -> {
            try {
                return service.request(PurchaseOrderDraftAction.CREATE, 1, 2, "run-1", draft(new BigDecimal("100")),
                        value -> requestId.complete((String) value.state().data().get("draftRequestId")));
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        var id = requestId.get(2, TimeUnit.SECONDS);
        service.resolveForUser(1, 2, id, new PurchaseDraftReceipt(
                PurchaseDraftReceipt.STATUS_EDITED, "PO2026112200001", "2124530107115659266", null,
                null, Map.of(
                        "supplierId", "1900000000000000001",
                        "warehouseId", "1930000000000000001",
                        "expectedArrivalDate", "2026-12-01",
                        "remark", "首批试单",
                        "items", List.of(Map.of(
                                "productId", "1940000000000000001",
                                "quantity", 80,
                                "unitPrice", "9.50",
                                "remark", ""))),
                "客户要求先小批量试单", null, null));
        var text = output.get(2, TimeUnit.SECONDS);
        assertThat(text).contains("用户修改参数后确认创建", "PO2026112200001", "最终参数：", "修改的字段：");
        assertThat(text).contains("items[0].quantity", "items[0].unitPrice", "expectedArrivalDate", "remark");
        assertThat(text).contains("用户说明：客户要求先小批量试单");
        assertThat(text).doesNotContain("items[0].productId");
    }

    @Test
    void reportsCreatedOrderNumberInPlainText() throws Exception {
        var service = service(Duration.ofSeconds(3));
        var requestId = new CompletableFuture<String>();
        var output = CompletableFuture.supplyAsync(() -> {
            try {
                return service.request(PurchaseOrderDraftAction.CREATE, 1, 2, "run-1", draft(new BigDecimal("100")),
                        value -> requestId.complete((String) value.state().data().get("draftRequestId")));
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        var id = requestId.get(2, TimeUnit.SECONDS);
        service.resolveForUser(1, 2, id, new PurchaseDraftReceipt(
                PurchaseDraftReceipt.STATUS_CREATED, "PO2026112300001", "2124530107115659266", "DRAFT",
                null, null, null, null, null));
        var createdText = output.get(2, TimeUnit.SECONDS);
        assertThat(createdText).contains("用户确认创建", "PO2026112300001", "已生成草稿");
        assertThat(createdText).contains("2124530107115659266");
    }

    @Test
    void reportsFailedReasonWithoutChangeSummary() throws Exception {
        var service = service(Duration.ofSeconds(3));
        var requestId = new CompletableFuture<String>();
        var output = CompletableFuture.supplyAsync(() -> {
            try {
                return service.request(PurchaseOrderDraftAction.CREATE, 1, 2, "run-1", draft(new BigDecimal("100")),
                        value -> requestId.complete((String) value.state().data().get("draftRequestId")));
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        var id = requestId.get(2, TimeUnit.SECONDS);
        service.resolveForUser(1, 2, id, new PurchaseDraftReceipt(
                PurchaseDraftReceipt.STATUS_FAILED, null, null, null, "采购单价低于参考采购价的 80%",
                Map.of(
                        "supplierId", "1900000000000000001",
                        "warehouseId", "1930000000000000001",
                        "items", List.of(Map.of(
                                "productId", "1940000000000000001",
                                "quantity", 80,
                                "unitPrice", 3,
                                "remark", ""))),
                null, null, null));
        var text = output.get(2, TimeUnit.SECONDS);
        assertThat(text).contains("创建未通过", "采购单价低于参考采购价的 80%", "被拒参数：");
        assertThat(text).doesNotContain("改动");
    }

    @Test
    void reportsRejectedReasonsAndSuggestion() throws Exception {
        var service = service(Duration.ofSeconds(3));
        var requestId = new CompletableFuture<String>();
        var output = CompletableFuture.supplyAsync(() -> {
            try {
                return service.request(PurchaseOrderDraftAction.CREATE, 1, 2, "run-1", draft(new BigDecimal("100")),
                        value -> requestId.complete((String) value.state().data().get("draftRequestId")));
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        var id = requestId.get(2, TimeUnit.SECONDS);
        service.resolveForUser(1, 2, id, new PurchaseDraftReceipt(
                PurchaseDraftReceipt.STATUS_REJECTED, null, null, null, null, null, null,
                List.of("价格过高", "数量不合适"), "希望分两批到货"));
        assertThat(output.get(2, TimeUnit.SECONDS)).contains(
                "用户驳回了采购草稿，未生成采购单", "驳回原因：价格过高、数量不合适", "补充要求：希望分两批到货");
    }

    @Test
    void rejectsRejectedReceiptWithoutReason() throws Exception {
        var service = service(Duration.ofSeconds(3));
        var requestId = new CompletableFuture<String>();
        CompletableFuture.supplyAsync(() -> {
            try {
                return service.request(PurchaseOrderDraftAction.CREATE, 1, 2, "run-1", draft(new BigDecimal("100")),
                        value -> requestId.complete((String) value.state().data().get("draftRequestId")));
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        var id = requestId.get(2, TimeUnit.SECONDS);
        assertThatThrownBy(() -> service.resolveForUser(1, 2, id, new PurchaseDraftReceipt(
                PurchaseDraftReceipt.STATUS_REJECTED, null, null, null, null, null, null, List.of(), " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("驳回原因");
    }

    @Test
    void validatesDraftBeforeWaitingForUser() throws Exception {
        var service = service(Duration.ofSeconds(3));
        var invalid = new PurchaseOrderDraftRequest(null, "1900000000000000001", "1930000000000000001",
                null, null, List.of());
        var result = service.request(PurchaseOrderDraftAction.CREATE, 1, 2, "run-1", invalid, value -> {
            throw new AssertionError("参数不合法时不应该发出确认事件");
        });
        assertThat(result).contains("items");
    }

    @Test
    void timeoutStopsWaiting() {
        var service = service(Duration.ofMillis(20));
        assertThatThrownBy(() -> service.request(PurchaseOrderDraftAction.CREATE, 1, 2, "run-1", draft(new BigDecimal("100")), value -> { }))
                .hasMessageContaining(PurchaseDraftService.TIMEOUT_MARKER);
    }

    /** 构造通过归属与权限校验的服务实例。 */
    private PurchaseDraftService service(Duration timeout) {
        when(conversations.selectOwned(2L, 1L)).thenReturn(new AiConversation());
        var user = new LoginUser();
        user.setPermissionCodes(List.of("ai:assistant:use"));
        when(users.findByUserId(1L)).thenReturn(user);
        return new PurchaseDraftService(conversations, users,
                mock(com.qiheng.erp.ai.conversation.ConversationMessageStore.class), mapper, timeout);
    }

    /** 构造一份合法草稿。 */
    private PurchaseOrderDraftRequest draft(BigDecimal quantity) {
        return new PurchaseOrderDraftRequest(null, "1900000000000000001", "1930000000000000001",
                null, null, List.of(new PurchaseOrderDraftRequest.Item(
                        "1940000000000000001", quantity, new BigDecimal("10.00"), null)));
    }
}