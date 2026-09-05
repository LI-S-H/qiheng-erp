package com.qiheng.erp.dashboard.domain.vo.todo;

import com.qiheng.erp.dashboard.domain.enums.DashboardTodoDetailModel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 工作台待办详情的模型基类。
 *
 * <p>具体内容由 {@link #model} 决定，避免把不同业务事实压缩成通用文本和键值指标。</p>
 */
@Data
@Schema(description = "工作台待办详情模型基类")
public abstract class DashboardTodoDetailVO {

    @Schema(description = "详情模型", requiredMode = Schema.RequiredMode.REQUIRED)
    private DashboardTodoDetailModel model;
}
