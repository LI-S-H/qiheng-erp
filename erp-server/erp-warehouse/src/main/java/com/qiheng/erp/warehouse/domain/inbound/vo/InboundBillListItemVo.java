package com.qiheng.erp.warehouse.domain.inbound.vo;

import com.qiheng.erp.warehouse.domain.stockbill.vo.StockBillListItemBaseVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Schema(description = "入库单列表项")
public class InboundBillListItemVo extends StockBillListItemBaseVo {
}
