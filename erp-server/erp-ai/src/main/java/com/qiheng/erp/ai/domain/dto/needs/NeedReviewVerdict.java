package com.qiheng.erp.ai.domain.dto.needs;

/**
 * 业务完整性审阅结论。
 *
 * <p>结构合法性由代码判，业务完整性由模型判：代码判不了"漏查了在途采购量"
 * 这类遗漏，模型判不了"依赖成环"。两者分开，谁也不越权。</p>
 *
 * @author Li
 * @since 2026-10-22
 */
public enum NeedReviewVerdict {

    /** 需求拆得完整，可以往下走。 */
    OK,

    /** 存在业务遗漏或判据不足，回 planner 修复。 */
    ISSUES
}
