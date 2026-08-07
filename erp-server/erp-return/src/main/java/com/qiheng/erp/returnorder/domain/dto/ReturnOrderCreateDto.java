package com.qiheng.erp.returnorder.domain.dto;

import com.qiheng.erp.returnorder.domain.port.ReturnType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 创建统一退货单草稿请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-07-31
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "创建统一退货单草稿请求")
public class ReturnOrderCreateDto extends ReturnOrderBaseDto {

    @NotNull(message = "退货类型不能为空")
    @Schema(description = "退货类型：PURCHASE_RETURN/SALES_RETURN")
    private ReturnType returnType;
}
