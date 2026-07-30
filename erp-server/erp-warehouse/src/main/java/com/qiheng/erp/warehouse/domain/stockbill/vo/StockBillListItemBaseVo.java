package com.qiheng.erp.warehouse.domain.stockbill.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.qiheng.erp.warehouse.domain.support.StockBillDetailVoMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 出入库工作单列表项公共字段。
 */
@Data
public class StockBillListItemBaseVo implements StockBillDetailVoMapping.QuantityTarget,
        StockBillDetailVoMapping.SummarySource {

    @Schema(description = "工作单 ID")
    private String workBillId;

    @Schema(description = "工作单号")
    private String billNo;

    @Schema(description = "来源类型")
    private String sourceType;

    @Schema(description = "来源单据 ID")
    private String sourceId;

    @Schema(description = "来源单据号")
    private String sourceNo;

    @Schema(description = "来源对象 ID")
    private String sourcePartyId;

    @Schema(description = "来源对象名称")
    private String sourcePartyName;

    @Schema(description = "录入方式")
    private String entryMode;

    @Schema(description = "仓库 ID")
    private String warehouseId;

    @Schema(description = "仓库名称")
    private String warehouseName;

    @Schema(description = "工作单状态")
    private String status;

    @Schema(description = "明细条数")
    private Integer itemCount;

    @Schema(description = "数量汇总描述")
    private String quantitySummary;

    @Schema(description = "本次工作单总数量")
    private Integer totalCurrentQty;

    @Schema(description = "主单位名称")
    private String quantityUnitName;

    @Schema(description = "确认人 ID")
    private String confirmedById;

    @Schema(description = "确认人姓名")
    private String confirmedByName;

    @Schema(description = "确认时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime confirmedAt;

    @Schema(description = "创建人 ID")
    private String createdById;

    @Schema(description = "创建人姓名")
    private String createdByName;

    @Schema(description = "业务负责人 ID")
    private String responsibleById;

    @Schema(description = "业务负责人姓名")
    private String responsibleByName;

    @Schema(description = "乐观锁版本号")
    private Integer version;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    @Schema(description = "工作单类型")
    private String billType;
}
