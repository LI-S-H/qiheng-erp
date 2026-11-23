package com.qiheng.erp.ai.sales;

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

/** 销售单提交与取消的等待通道：三种处置结果与回执内容。 */
class SalesOrderActionServiceTest {

    private final AiConversationMapper conversations = mock(AiConversationMapper.class);
    private final LoginUserService users = mock(LoginUserService.class);

    @Test
    void reportsSubmittedOrder() throws Exception {
        var text = capture(SalesOrderActionType.SUBMIT, new SalesOrderActionReceipt(
                SalesOrderActionReceipt.STATUS_SUBMITTED, null, "SO2026112300003", "SUBMITTED",
                null, null, "2026-12-08", null));
        assertThat(text).contains("用户同意提交", "SO2026112300003", "已提交审核", "待审核", "预计发货：2026-12-08");
    }

    @Test
    void reportsSubmitRejectedByUser() throws Exception {
        var text = capture(SalesOrderActionType.SUBMIT, new SalesOrderActionReceipt(
                SalesOrderActionReceipt.STATUS_REJECTED, null, "SO2026112300003", null,
                null, "客户还在确认数量", null, null));
        assertThat(text).contains("用户暂不提交", "保持草稿状态", "客户还在确认数量");
    }

    @Test
    void reportsSubmitFailedWithOrderParams() throws Exception {
        var text = capture(SalesOrderActionType.SUBMIT, new SalesOrderActionReceipt(
                SalesOrderActionReceipt.STATUS_FAILED, null, "SO2026112300003", null,
                "可用库存不足，仓库 WH001 缺 12 件", null, null,
                Map.of("expectedDeliveryDate", "", "warehouseId", "1930000000000000001")));
        assertThat(text).contains("提交未通过", "可用库存不足", "当前单据：");
    }

    @Test
    void reportsCancelledOrder() throws Exception {
        var text = capture(SalesOrderActionType.CANCEL, new SalesOrderActionReceipt(
                SalesOrderActionReceipt.STATUS_CANCELLED, null, "SO2026112300003", "CANCELLED",
                null, "客户取消采购计划", null, null));
        assertThat(text).contains("用户同意取消", "SO2026112300003", "已取消", "客户取消采购计划");
    }

    @Test
    void reportsCancelFailed() throws Exception {
        var text = capture(SalesOrderActionType.CANCEL, new SalesOrderActionReceipt(
                SalesOrderActionReceipt.STATUS_FAILED, null, "SO2026112300003", null,
                "已出库的销售单不能取消", null, null, null));
        assertThat(text).contains("取消失败", "已出库的销售单不能取消");
    }

    /** 发起一次动作并提交处置结果。 */
    private String capture(SalesOrderActionType type, SalesOrderActionReceipt receipt) throws Exception {
        when(conversations.selectOwned(2L, 1L)).thenReturn(new AiConversation());
        var user = new LoginUser();
        user.setPermissionCodes(List.of("ai:assistant:use"));
        when(users.findByUserId(1L)).thenReturn(user);
        var service = new SalesOrderActionService(conversations, users,
                mock(ConversationMessageStore.class), new ObjectMapper(), Duration.ofSeconds(3));
        var requestId = new CompletableFuture<String>();
        var output = CompletableFuture.supplyAsync(() -> {
            try {
                return service.request(type, 1, 2, "run-1",
                        new SalesOrderActionRequest("2124555223207800833", "SO2026112300003", null),
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