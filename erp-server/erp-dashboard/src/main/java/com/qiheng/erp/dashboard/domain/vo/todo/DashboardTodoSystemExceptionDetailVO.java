package com.qiheng.erp.dashboard.domain.vo.todo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/** 工作台系统异常待办详情。 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工作台系统异常待办详情")
public class DashboardTodoSystemExceptionDetailVO extends DashboardTodoDetailVO {

    @Schema(description = "代表性系统异常列表")
    private List<DashboardTodoSystemExceptionItemVO> items = new ArrayList<>();
}
