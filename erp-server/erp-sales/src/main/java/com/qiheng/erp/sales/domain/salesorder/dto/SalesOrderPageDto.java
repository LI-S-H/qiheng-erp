package com.qiheng.erp.sales.domain.salesorder.dto;

import com.qiheng.erp.common.dto.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 销售订单分页查询请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-08-10
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "销售订单分页查询请求")
public class SalesOrderPageDto extends PageQuery {

    @Schema(description = "销售单号，对应 `sales_order.sales_no`，使用包含匹配")
    @Size(max = 64, message = "销售单号长度不能超过64个字符")
    private String salesNo;

    @Schema(description = "客户 ID，对应 `sales_order.customer_id`，使用精确匹配；前端按字符串传输 BIGINT")
    private String customerId;

    @Schema(description = "出库仓库 ID，对应 `sales_order.warehouse_id`，使用精确匹配；前端按字符串传输 BIGINT")
    private String warehouseId;

    @Schema(description = "状态：DRAFT、SUBMITTED、APPROVED、PARTIAL_OUTBOUND、OUTBOUND_DONE、CANCELLED，使用精确匹配")
    private String status;
}