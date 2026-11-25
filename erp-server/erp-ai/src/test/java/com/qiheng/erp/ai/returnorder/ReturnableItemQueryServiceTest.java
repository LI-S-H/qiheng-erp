package com.qiheng.erp.ai.returnorder;

import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.returnorder.domain.port.ReturnType;
import com.qiheng.erp.returnorder.domain.vo.ReturnOrderItemVo;
import com.qiheng.erp.returnorder.domain.vo.ReturnableSourceDetailVo;
import com.qiheng.erp.returnorder.service.IReturnOrderService;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 可退来源查询入口：权限、退货方向与来源单号的输入校验。
 *
 * @author Li
 * @since 2026-11-24
 */
@ExtendWith(MockitoExtension.class)
class ReturnableItemQueryServiceTest {

    @Mock
    private IReturnOrderService returnOrders;
    @Mock
    private LoginUserService users;

    @Test
    void returnsSourceForUserWithReturnQueryPermission() {
        when(users.findByUserId(7L)).thenReturn(user("return:query"));
        when(returnOrders.getReturnableSourceByNo(ReturnType.SALES_RETURN, "SO2026112300001"))
                .thenReturn(source());

        ReturnableSourceDetailVo result = service().load(7L, "SALES_RETURN", "SO2026112300001");

        assertThat(result.getSourceOrderNo()).isEqualTo("SO2026112300001");
        assertThat(result.getItems()).singleElement()
                .extracting(ReturnOrderItemVo::getAvailableReturnQty)
                .isEqualTo(new BigDecimal("12.00"));
    }

    @Test
    void rejectsUserWithoutReturnQueryPermission() {
        when(users.findByUserId(7L)).thenReturn(user("sales:create"));

        assertThatThrownBy(() -> service().load(7L, "SALES_RETURN", "SO2026112300001"))
                .hasMessageContaining("退货查询权限");
    }

    @Test
    void rejectsUnknownReturnType() {
        when(users.findByUserId(7L)).thenReturn(user("return:query"));

        assertThatThrownBy(() -> service().load(7L, "UNKNOWN", "SO2026112300001"))
                .hasMessageContaining("退货类型");
    }

    @Test
    void propagatesMissingSourceMessage() {
        when(users.findByUserId(7L)).thenReturn(user("return:query"));
        when(returnOrders.getReturnableSourceByNo(ReturnType.SALES_RETURN, "SO2026112399999"))
                .thenThrow(new BizException(10002, "来源订单不存在或不符合退货条件"));

        assertThatThrownBy(() -> service().load(7L, "SALES_RETURN", "SO2026112399999"))
                .hasMessageContaining("来源订单不存在");
    }

    private ReturnableItemQueryService service() {
        return new ReturnableItemQueryService(returnOrders, users);
    }

    private LoginUser user(String permission) {
        LoginUser user = new LoginUser();
        user.setPermissionCodes(List.of(permission));
        return user;
    }

    private ReturnableSourceDetailVo source() {
        ReturnOrderItemVo item = new ReturnOrderItemVo();
        item.setSourceOrderItemId(2_001L);
        item.setProductCode("P-101");
        item.setProductName("测试产品");
        item.setUnitName("件");
        item.setQuantityPrecision(0);
        item.setSourceFulfilledQty(new BigDecimal("12.00"));
        item.setOccupiedQty(BigDecimal.ZERO);
        item.setStockAvailableQty(BigDecimal.ZERO);
        item.setAvailableReturnQty(new BigDecimal("12.00"));

        ReturnableSourceDetailVo source = new ReturnableSourceDetailVo();
        source.setReturnType("SALES_RETURN");
        source.setSourceOrderId(1_001L);
        source.setSourceOrderNo("SO2026112300001");
        source.setSourceOrderStatus("OUTBOUND_DONE");
        source.setWarehouseId(501L);
        source.setWarehouseName("主仓");
        source.setItems(List.of(item));
        return source;
    }
}