package com.qiheng.erp.warehouse.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "入库单分页查询响应")
public class InboundBillPageVo {

    @Schema(description = "入库单记录列表")
    private List<InboundBillListItemVo> records;

    @Schema(description = "总记录数")
    private Integer total;

    @Schema(description = "当前页码")
    private int pageNum;

    @Schema(description = "每页条数")
    private int pageSize;
}