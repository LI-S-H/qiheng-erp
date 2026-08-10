package com.qiheng.erp.sales.domain.salesorder.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * <p>
 * 销售订单详情响应 VO（主表 + 明细数组）
 * </p>
 *
 * @author Li
 * @since 2026-08-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "销售订单详情响应")
public class SalesOrderDetailVo extends SalesOrderVo {

    @Schema(description = "销售订单明细列表")
    private List<SalesOrderItemVo> items;
}