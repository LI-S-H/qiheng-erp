package com.qiheng.erp.returnorder.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/** 退货单详情。 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "退货单详情响应")
public class ReturnOrderDetailVo extends ReturnOrderVo {
    @Schema(description = "退货单明细列表")
    private List<ReturnOrderItemVo> items;
}