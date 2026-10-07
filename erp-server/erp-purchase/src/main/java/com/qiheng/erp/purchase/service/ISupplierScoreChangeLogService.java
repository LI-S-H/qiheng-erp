package com.qiheng.erp.purchase.service;

import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreChangeBatchCommand;
import com.qiheng.erp.purchase.domain.supplierscore.dto.SupplierScoreChangeLogPageDto;
import com.qiheng.erp.purchase.domain.supplierscore.vo.SupplierScoreChangeLogVo;

/**
 * <p>
 * 供应商 / 供货产品 评分变更日志服务接口
 * </p>
 *
 * <p>承担两类职责:</p>
 * <ul>
 *   <li>统一分页查询评分变更日志，支持供应商、供货关系及指标等条件按 AND 筛选</li>
 *   <li>写入入口 {@link #appendBatch(ScoreChangeBatchCommand)}:由 SupplierScoreRecalculateService 在重算完成后调用,
 *       同一业务事务内写入,changeKey 走 DB UNIQUE INDEX 幂等</li>
 * </ul>
 *
 * @author Li
 * @since 2026-09-21
 */
public interface ISupplierScoreChangeLogService {

    /** 按可选条件分页查询评分变更记录；不传 ID 时查询全部。 */
    PageResult<SupplierScoreChangeLogVo> pageScoreChangeLogs(SupplierScoreChangeLogPageDto dto);

    /**
     * 批量写入评分变更日志(同步,业务事务内调用)。
     *
     * <p>每条 entry 自动计算 changeKey,依赖 DB UNIQUE INDEX 实现幂等。
     * MQ 重投 / Consumer 重试下不重复写入。</p>
     *
     * @param command 批量写入入参;为 null 或 entries 为空时直接返回
     */
    void appendBatch(ScoreChangeBatchCommand command);
}
