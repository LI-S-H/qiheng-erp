package com.qiheng.erp.warehouse.domain.stockbill.dto;

import com.qiheng.erp.common.dto.PageQuery;
import com.qiheng.erp.warehouse.domain.common.enums.EntryMode;
import com.qiheng.erp.warehouse.domain.common.enums.SourceType;
import com.qiheng.erp.warehouse.domain.stockbill.enums.StockBillType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Schema(description = "库存流水分页查询请求")
public class StockBillPageDto extends PageQuery {

    @Schema(description = "库存流水号，对应 stock_bill.bill_no，使用包含匹配")
    private String billNo;

    @Schema(description = "来源业务单号，对应 stock_bill.business_source_no，使用包含匹配")
    private String sourceNo;

    @Schema(
            description = "来源业务类型，精确匹配；这是派生查询参数，不对应 stock_bill 持久化列",
            allowableValues = {
                    "PURCHASE_ORDER",
                    "SALES_ORDER",
                    "PURCHASE_RETURN_ORDER",
                    "SALES_RETURN_ORDER",
                    "STOCK_ADJUST"
            }
    )
    private SourceType sourceType;

    @Schema(description = "仓库 ID，对应 stock_bill.warehouse_id，使用精确匹配")
    private String warehouseId;

    @Schema(
            description = "库存流水类型，对应 stock_bill.bill_type，使用精确匹配",
            allowableValues = {
                    "PURCHASE_IN",
                    "SALES_OUT",
                    "PURCHASE_RETURN",
                    "SALES_RETURN",
                    "ADJUST_IN"
            }
    )
    private StockBillType billType;

    @Schema(
            description = "录入方式，对应 stock_bill.entry_mode，使用精确匹配；分别表示系统自动录入、人工补录和人工调整",
            allowableValues = {
                    "SOURCE_GENERATED",
                    "MANUAL_SUPPLEMENT",
                    "MANUAL_ADJUSTMENT"

            }
    )
    private EntryMode entryMode;
}