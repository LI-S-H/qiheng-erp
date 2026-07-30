package com.qiheng.erp.warehouse.domain.inbound.vo;

import com.qiheng.erp.warehouse.domain.stockbill.vo.StockBillSummaryBaseVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Schema(description = "入库单分页汇总信息")
public class InboundBillSummaryVo extends StockBillSummaryBaseVo {
}
