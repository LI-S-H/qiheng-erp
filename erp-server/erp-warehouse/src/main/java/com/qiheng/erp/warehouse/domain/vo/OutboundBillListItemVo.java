package com.qiheng.erp.warehouse.domain.vo;

import com.qiheng.erp.warehouse.domain.vo.common.StockBillListItemBaseVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 出库单列表项。
 *
 * @author Li
 * @since 2026-07-26
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Schema(description = "出库单列表项")
public class OutboundBillListItemVo extends StockBillListItemBaseVo {
}
