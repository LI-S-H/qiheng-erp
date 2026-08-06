package com.qiheng.erp.returnorder.domain.dto;

import com.qiheng.erp.common.dto.PageQuery;
import com.qiheng.erp.returnorder.domain.enums.ReturnStatus;
import com.qiheng.erp.returnorder.domain.port.ReturnType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 统一退货单分页查询参数。
 *
 * <p>供应商和客户统一映射为 {@code partyId}，避免在统一入口中出现业务方向专属参数。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Schema(description = "统一退货单分页查询请求")
public class ReturnOrderPageDto extends PageQuery {

    @Schema(description = "退货类型，对应 return_order.return_type，使用精确匹配")
    private ReturnType returnType;

    @Schema(description = "退货单号，对应 return_order.return_no，使用包含匹配")
    @Size(max = 64, message = "退货单号长度不能超过64个字符")
    private String returnNo;

    @Schema(description = "来源单据号，对应 return_order.source_order_no，使用包含匹配")
    @Size(max = 64, message = "来源单据号长度不能超过64个字符")
    private String sourceOrderNo;

    @Schema(description = "往来方ID（供应商/客户ID），对应 return_order.party_id，使用精确匹配")
    private String partyId;

    @Schema(description = "仓库ID，对应 return_order.warehouse_id，使用精确匹配")
    private String warehouseId;

    @Schema(description = "退货单状态，对应 return_order.status，使用精确匹配")
    private ReturnStatus status;
}
