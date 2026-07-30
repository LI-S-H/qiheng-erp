package com.qiheng.erp.purchase.domain.purchaseorder.dto;

import com.qiheng.erp.common.dto.PageQuery;
import com.qiheng.erp.purchase.domain.purchaseorder.enums.PurchaseOrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 采购订单分页查询请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-07-31
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "采购订单分页查询请求")
public class PurchaseOrderPageDto extends PageQuery {

    @Schema(description = "采购单号，对应 purchase_order.purchase_no，使用包含匹配")
    private String purchaseNo;

    @Schema(description = "供应商 ID，对应 purchase_order.supplier_id，使用精确匹配")
    private String supplierId;

    @Schema(description = "入库仓库 ID，对应 purchase_order.warehouse_id，使用精确匹配")
    private String warehouseId;

    @Schema(
            description = "采购订单状态，对应 purchase_order.status，使用精确匹配",
            allowableValues = {
                    "DRAFT",
                    "SUBMITTED",
                    "APPROVED",
                    "PARTIAL_INBOUND",
                    "INBOUND_DONE",
                    "CANCELLED"
            }
    )
    private PurchaseOrderStatus status;
}
