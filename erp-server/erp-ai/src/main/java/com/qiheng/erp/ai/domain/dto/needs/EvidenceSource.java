package com.qiheng.erp.ai.domain.dto.needs;

/**
 * 证据项的产出方式：由哪一类执行器产出这个证据。
 *
 * <p>这个枚举是需求层与执行层之间唯一的耦合点：主循环的差集驱动按它决定
 * 步骤类型与路由，模型不需要（也不允许）指定具体 SQL、代码或执行器。</p>
 *
 * @author Li
 * @since 2026-10-22
 */
public enum EvidenceSource {

    /** 由受控 SQL 取数得到。 */
    SQL,

    /** 由 Python 沙箱计算得到，输入是其它证据项。 */
    PYTHON,

    /**
     * 由方案子图给出的方案清单，不单独取数。
     *
     * <p>只有 {@code ADVICE} 需求会用它，且每条 ADVICE 需求恰好一项：这一项的值就是
     * 候选方案（含业务动作、组合方式、硬约束与软条件），方案确认后再派生需求把细节算清。</p>
     */
    PROPOSAL
}
