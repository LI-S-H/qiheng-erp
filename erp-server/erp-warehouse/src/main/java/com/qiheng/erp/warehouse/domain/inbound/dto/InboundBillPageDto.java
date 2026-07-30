package com.qiheng.erp.warehouse.domain.inbound.dto;

import com.qiheng.erp.common.dto.PageQuery;
import com.qiheng.erp.warehouse.domain.common.enums.EntryMode;
import com.qiheng.erp.warehouse.domain.stockbill.enums.StockBillStatus;
import com.qiheng.erp.warehouse.domain.inbound.enums.InboundType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Schema(description = "入库单分页查询请求")
public class InboundBillPageDto extends PageQuery {

    @Schema(description = "入库单号，对应 inbound_bill.inbound_no，使用包含匹配")
    private String billNo;

    @Schema(description = "来源单据号，对应 inbound_bill.source_no，使用包含匹配")
    private String sourceNo;

    @Schema(description = "仓库 ID，对应 inbound_bill.warehouse_id，使用精确匹配")
    private String warehouseId;

    @Schema(
            description = "入库单类型，对应 inbound_bill.bill_type，使用精确匹配",
            allowableValues = {
                    "PURCHASE_IN",
                    "SALES_RETURN",
                    "ADJUST_IN"
            }
    )
    private InboundType billType;

    @Schema(
            description = "录入方式，对应 inbound_bill.entry_mode，使用精确匹配",
            allowableValues = {
                    "SOURCE_GENERATED",
                    "MANUAL_SUPPLEMENT",
                    "MANUAL_ADJUSTMENT"
            }
    )
    private EntryMode entryMode;

    @Schema(
            description = "入库单状态，对应 inbound_bill.status，使用精确匹配",
            allowableValues = {
                    "DRAFT",
                    "PENDING_CONFIRM",
                    "CONFIRMED",
                    "CANCELLED"
            }
    )
    private StockBillStatus status;
}