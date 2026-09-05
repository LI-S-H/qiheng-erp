package com.qiheng.erp.dashboard.domain.vo.todo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/** 工作台库存风险待办详情。 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工作台库存风险待办详情")
public class DashboardTodoStockRiskDetailVO extends DashboardTodoDetailVO {

    @Schema(description = "代表性库存风险 SKU 列表")
    private List<DashboardTodoStockRiskItemVO> items = new ArrayList<>();
}
