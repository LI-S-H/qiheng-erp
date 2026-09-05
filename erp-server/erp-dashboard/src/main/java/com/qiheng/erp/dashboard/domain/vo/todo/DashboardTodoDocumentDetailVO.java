package com.qiheng.erp.dashboard.domain.vo.todo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/** 工作台单据类待办详情。 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工作台单据类待办详情")
public class DashboardTodoDocumentDetailVO extends DashboardTodoDetailVO {

    @Schema(description = "代表性单据列表")
    private List<DashboardTodoDocumentItemVO> items = new ArrayList<>();
}
