package com.qiheng.erp.purchase.domain.supplierscore.dto;

/**
 * 供应商价格汇总权重对:单个供货关系的 score_basis_amount 与该 SP 的 price_score。
 *
 * <p>用 record 而不是 long[] 是为了编译期类型安全与字段自描述,避免调用方传反
 * pair[0]/pair[1] 顺序导致的加权结果错误。</p>
 *
 * <p>构造器校验把字段合法性前置,聚合器内部不再重复防御:</p>
 * <ul>
 *   <li>spAmount 必须 > 0:score_basis_amount 是有效入库金额,<=0 没有加权意义</li>
 *   <li>priceScore 必须 ∈ [0, 10000]:业务上 price_score 是 INT×100 业务小数(0~100),×100 后 0~10000</li>
 * </ul>
 *
 * @param spAmount 该 SP 的 score_basis_amount(分),必须为正
 * @param priceScore 该 SP 的 price_score(INT×100),必须 0~10000
 * @author Li
 * @since 2026-09-23
 */
public record SupplierPricePair(long spAmount, int priceScore) {
    public SupplierPricePair {
        if (spAmount <= 0) {
            throw new IllegalArgumentException("spAmount 必须为正,实际=" + spAmount);
        }
        if (priceScore < 0 || priceScore > 10000) {
            throw new IllegalArgumentException("priceScore 必须在 0~10000,实际=" + priceScore);
        }
    }
}