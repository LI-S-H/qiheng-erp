package com.qiheng.erp.dashboard.domain.vo.todo;

import com.qiheng.erp.dashboard.domain.enums.DashboardTodoWaitLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 工作台待办中的代表性业务单据。
 *
 * <p>字段标签由所属的 {@code detail.model} 固定：采购退货使用“原采购单号/供应商”，
 * 销售退货使用“原销售单号/客户”，出入库使用“来源单号/仓库”。</p>
 */
@Data
@Schema(description = "工作台待办代表性业务单据")
public class DashboardTodoDocumentItemVO {

    @Schema(description = "当前业务单号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String documentNo;

    @Schema(description = "来源业务单号；无来源单据时为 null")
    private String sourceDocumentNo;

    @Schema(description = "供应商、客户或仓库名称；标签由详情模型决定")
    private String counterpartyName;

    @Schema(description = "金额，单位为分；无金额事实时为 null")
    private Long amountFen;

    @Schema(description = "等待时长，单位为小时；无等待事实时为 null")
    private Long waitHours;

    @Schema(description = "等待时长分级；无等待事实时为 null")
    private DashboardTodoWaitLevel waitLevel;

    @Schema(description = "当前单据业务状态", requiredMode = Schema.RequiredMode.REQUIRED)
    private String documentStatus;
}
