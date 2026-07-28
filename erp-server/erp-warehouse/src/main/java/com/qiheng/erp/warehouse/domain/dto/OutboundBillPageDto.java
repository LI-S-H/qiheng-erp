package com.qiheng.erp.warehouse.domain.dto;

import com.qiheng.erp.common.dto.PageQuery;
import com.qiheng.erp.warehouse.domain.enums.EntryMode;
import com.qiheng.erp.warehouse.domain.enums.StockBillStatus;
import com.qiheng.erp.warehouse.domain.enums.OutboundType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 出库单分页查询请求
 *
 * @author Li
 * @since 2026-07-26
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Schema(description = "出库单分页查询请求")
public class OutboundBillPageDto extends PageQuery {

    @Schema(description = "出库单号，对应 outbound_bill.outbound_no，使用包含匹配")
    private String billNo;

    @Schema(description = "来源单据号，对应 outbound_bill.source_no，使用包含匹配")
    private String sourceNo;

    @Schema(description = "仓库 ID，对应 outbound_bill.warehouse_id，使用精确匹配")
    private String warehouseId;

    @Schema(
            description = "出库单类型，对应 outbound_bill.outbound_type，使用精确匹配",
            allowableValues = {"SALES_OUT", "PURCHASE_RETURN", "ADJUST_OUT"}
    )
    private OutboundType billType;

    @Schema(
            description = "录入方式，对应 outbound_bill.entry_mode，使用精确匹配",
            allowableValues = {"SOURCE_GENERATED", "MANUAL_SUPPLEMENT", "MANUAL_ADJUSTMENT"}
    )
    private EntryMode entryMode;

    @Schema(
            description = "出库单状态，对应 outbound_bill.status，使用精确匹配",
            allowableValues = {"DRAFT", "PENDING_CONFIRM", "CONFIRMED", "CANCELLED"}
    )
    private StockBillStatus status;
}
