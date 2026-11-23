package com.qiheng.erp.ai.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.conversation.ConversationMessageStore;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 采购单提交与取消的等待通道：三种处置结果与回执内容。 */
class PurchaseOrderActionServiceTest {

    private final AiConversationMapper conversations = mock(AiConversationMapper.class);
    private final LoginUserService users = mock(LoginUserService.class);

    @Test
    void reportsSubmittedOrder() throws Exception {
        var text = capture(PurchaseOrderActionType.SUBMIT, new PurchaseOrderActionReceipt(
                PurchaseOrderActionReceipt.STATUS_SUBMITTED, null, "PO2026112300003", "SUBMITTED",
                null, null, "2026-12-08", null));
        assertThat(text).contains("用户同意提交", "PO2026112300003", "已提交审核", "待审核", "预计到货：2026-12-08");
    }

    @Test
    void reportsSubmitRejectedByUser() throws Exception {
        var text = capture(PurchaseOrderActionType.SUBMIT, new PurchaseOrderActionReceipt(
                PurchaseOrderActionReceipt.STATUS_REJECTED, null, "PO2026112300003", null,
                null, "还想再比一下价格", null, null));
        assertThat(text).contains("用户暂不提交", "保持草稿状态", "还想再比一下价格");
    }

    @Test
    void reportsSubmitFailedWithOrderParams() throws Exception {
        var text = capture(PurchaseOrderActionType.SUBMIT, new PurchaseOrderActionReceipt(
                PurchaseOrderActionReceipt.STATUS_FAILED, null, "PO2026112300003", null,
                "预计到货日期不能为空", null, null,
                Map.of("expectedArrivalDate", "", "supplierId", "1900000000000000001")));
        assertThat(text).contains("提交未通过", "预计到货日期不能为空", "当前单据：");
    }

    @Test
    void reportsCancelledOrder() throws Exception {
        var text = capture(PurchaseOrderActionType.CANCEL, new PurchaseOrderActionReceipt(
                PurchaseOrderActionReceipt.STATUS_CANCELLED, null, "PO2026112300003", "CANCELLED",
                null, "供应商交期无法满足", null, null));
        assertThat(text).contains("用户同意取消", "PO2026112300003", "已取消", "供应商交期无法满足");
    }

    @Test
    void reportsCancelFailed() throws Exception {
        var text = capture(PurchaseOrderActionType.CANCEL, new PurchaseOrderActionReceipt(
                PurchaseOrderActionReceipt.STATUS_FAILED, null, "PO2026112300003", null,
                "已审核的采购单存在入库事实，不能取消", null, null, null));
        assertThat(text).contains("取消失败", "存在入库事实");
    }

    /** 发起一次动作并提交处置结果。 */
    private String capture(PurchaseOrderActionType type, PurchaseOrderActionReceipt receipt) throws Exception {
        when(conversations.selectOwned(2L, 1L)).thenReturn(new AiConversation());
        var user = new LoginUser();
        user.setPermissionCodes(List.of("ai:assistant:use"));
        when(users.findByUserId(1L)).thenReturn(user);
        var service = new PurchaseOrderActionService(conversations, users,
                mock(ConversationMessageStore.class), new ObjectMapper(), Duration.ofSeconds(3));
        var requestId = new CompletableFuture<String>();
        var output = CompletableFuture.supplyAsync(() -> {
            try {
                return service.request(type, 1, 2, "run-1",
                        new PurchaseOrderActionRequest("2124555223207800833", "PO2026112300003", null),
                        value -> requestId.complete((String) value.state().data().get("actionRequestId")));
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        var id = requestId.get(2, TimeUnit.SECONDS);
        service.resolveForUser(1, 2, id, receipt);
        return output.get(2, TimeUnit.SECONDS);
    }
}