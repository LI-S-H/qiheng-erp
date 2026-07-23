package com.qiheng.erp.warehouse.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "库存流水分页查询响应")
public class StockBillPageVo {

    @Schema(description = "库存流水记录列表")
    private List<StockBillListItemVo> records;

    @Schema(description = "总记录数")
    private Integer total;

    @Schema(description = "当前页码")
    private int pageNum;

    @Schema(description = "每页条数")
    private int pageSize;
}