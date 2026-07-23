package com.qiheng.erp.warehouse.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.qiheng.erp.warehouse.domain.enums.EntryMode;
import com.qiheng.erp.warehouse.domain.enums.SourceType;
import com.qiheng.erp.warehouse.domain.enums.StockBillType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "库存流水列表项")
public class StockBillListItemVo {

    @Schema(description = "库存流水ID，对应 stock_bill.id")
    private Long stockLedgerId;

    @Schema(description = "库存流水号，对应 stock_bill.bill_no")
    private String billNo;

    @Schema(description = "出入库类型")
    private StockBillType billType;

    @Schema(description = "录入方式")
    private EntryMode entryMode;

    @Schema(description = "原业务来源类型")
    private SourceType sourceType;

    @Schema(description = "原业务单据 ID")
    private Long sourceId;

    @Schema(description = "原业务单号或调整单号")
    private String sourceNo;

    @Schema(description = "仓库 ID")
    private Long warehouseId;

    @Schema(description = "仓库名称")
    private String warehouseName;

    @Schema(description = "明细条数")
    private Integer itemCount;

    @Schema(description = "确认人 ID")
    private Long confirmedById;

    @Schema(description = "确认人姓名")
    private String confirmedByName;

    @Schema(description = "确认时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime confirmedAt;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}