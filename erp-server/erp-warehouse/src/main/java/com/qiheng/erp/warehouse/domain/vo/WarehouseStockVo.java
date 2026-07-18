package com.qiheng.erp.warehouse.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "库存余额响应")
public class WarehouseStockVo {

    @Schema(description = "库存ID")
    private Long stockId;

    @Schema(description = "仓库ID")
    private Long warehouseId;

    @Schema(description = "仓库编码")
    private String warehouseCode;

    @Schema(description = "仓库名称")
    private String warehouseName;

    @Schema(description = "产品ID")
    private Long productId;

    @Schema(description = "产品编码")
    private String productCode;

    @Schema(description = "产品名称")
    private String productName;

    @Schema(description = "单位名称")
    private String unitName;

    @Schema(description = "当前库存")
    private BigDecimal stockQty;

    @Schema(description = "锁定库存")
    private BigDecimal lockedQty;

    @Schema(description = "可用库存，服务层计算 stock_qty - locked_qty")
    private BigDecimal availableQty;

    @Schema(description = "安全库存")
    private BigDecimal safetyStockQty;

    @Schema(description = "乐观锁版本号")
    private Integer version;

    @Schema(description = "更新时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}