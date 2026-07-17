package com.qiheng.erp.warehouse.domain.dto;

import com.qiheng.erp.common.dto.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "仓库分页查询请求")
public class WarehousePageDto extends PageQuery {

    @Schema(description = "仓库编码（包含匹配）")
    private String warehouseCode;

    @Schema(description = "仓库名称（包含匹配）")
    private String warehouseName;

    @Schema(description = "联系人（包含匹配）")
    private String contactName;

    @Schema(description = "联系电话（包含匹配）")
    private String contactPhone;

    @Schema(description = "状态：1启用，0禁用")
    private Integer status;
}