package com.qiheng.erp.warehouse.domain.dto;

import com.qiheng.erp.common.dto.PageQuery;
import com.qiheng.erp.warehouse.domain.enums.InboundBillStatus;
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
    private String inboundNo;

    @Schema(description = "来源单据号，对应 inbound_bill.source_no，使用包含匹配")
    private String sourceNo;

    @Schema(
            description = "来源对象名称，对应 inbound_bill.source_party_name，使用包含匹配"
    )
    private String sourcePartyName;

    @Schema(description = "仓库 ID，对应 inbound_bill.warehouse_id，使用精确匹配")
    private String warehouseId;

    @Schema(
            description = "入库单状态，对应 inbound_bill.status，使用精确匹配",
            allowableValues = {
                    "DRAFT",
                    "PENDING_CONFIRM",
                    "CONFIRMED",
                    "CANCELLED"
            }
    )
    private InboundBillStatus status;
}