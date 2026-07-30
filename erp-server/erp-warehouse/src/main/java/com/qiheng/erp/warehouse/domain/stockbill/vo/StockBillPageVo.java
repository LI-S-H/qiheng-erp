package com.qiheng.erp.warehouse.domain.stockbill.vo;

import com.qiheng.erp.warehouse.domain.common.vo.PageRespVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "库存流水分页查询响应")
public class StockBillPageVo extends PageRespVo<StockBillListItemVo> {
}
