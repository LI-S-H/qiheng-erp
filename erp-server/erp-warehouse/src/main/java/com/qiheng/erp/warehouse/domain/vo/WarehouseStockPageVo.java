package com.qiheng.erp.warehouse.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "库存分页查询响应")
public class WarehouseStockPageVo {

    @Schema(description = "库存记录列表")
    private List<WarehouseStockVo> records;

    @Schema(description = "可选；库存余额默认使用无总数分页，后端不需要返回精确总数")
    private Integer total;

    @Schema(description = "是否存在下一页")
    private boolean hasNext;

    @Schema(description = "当前页码")
    private int pageNum;

    @Schema(description = "每页条数")
    private int pageSize;

    @Schema(description = "汇总信息")
    private WarehouseStockSummaryVo summary;
}