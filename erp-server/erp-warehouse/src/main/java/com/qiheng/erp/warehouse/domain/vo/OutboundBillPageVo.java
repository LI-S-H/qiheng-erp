package com.qiheng.erp.warehouse.domain.vo;

import com.qiheng.erp.warehouse.domain.vo.common.PageRespVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 出库单分页查询响应
 *
 * @author Li
 * @since 2026-07-26
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "出库单分页查询响应")
public class OutboundBillPageVo extends PageRespVo<OutboundBillListItemVo> {

    @Schema(description = "汇总信息")
    private OutboundBillSummaryVo summary;
}
