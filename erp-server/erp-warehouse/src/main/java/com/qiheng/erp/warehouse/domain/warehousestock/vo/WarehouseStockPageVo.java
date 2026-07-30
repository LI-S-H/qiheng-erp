package com.qiheng.erp.warehouse.domain.warehousestock.vo;

import com.qiheng.erp.warehouse.domain.common.vo.PageRespVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "库存分页查询响应")
public class WarehouseStockPageVo extends PageRespVo<WarehouseStockVo> {

    @Schema(description = "是否存在下一页")
    private boolean hasNext;

    @Schema(description = "汇总信息")
    private WarehouseStockSummaryVo summary;
}
