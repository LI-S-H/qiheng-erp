package com.qiheng.erp.warehouse.domain.vo;

import com.qiheng.erp.warehouse.domain.vo.common.PageRespVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "入库单分页查询响应")
public class InboundBillPageVo extends PageRespVo<InboundBillListItemVo> {

    @Schema(description = "汇总信息")
    private InboundBillSummaryVo summary;
}
