package com.qiheng.erp.common.scoring;

/**
 * 产品参考采购价变化处理器(反转接口模式,定义在 erp-common)。
 *
 * <p>设计原因:erp-product 模块单向不依赖 erp-purchase,但产品参考价变化需要
 * 触发 erp-purchase 模块的评分重算。通过反转接口在 erp-common 定义接口,
 * erp-purchase 实现,erp-product 通过 Spring 注入调用,避免循环依赖。</p>
 *
 * <p>调用约定:</p>
 * <ul>
 *   <li>由 erp-product.ProductServiceImpl.updateReferencePrice 在同一事务内同步调用;</li>
 *   <li>失败抛出异常触发事务回滚,产品参考价更新整体失败;</li>
 *   <li>实现方的事务传播应使用 Propagation.MANDATORY(必须在外层事务中运行)。</li>
 * </ul>
 *
 * @author Li
 * @since 2026-09-25
 */
public interface ProductReferencePriceChangeHandler {

    /**
     * 产品参考采购价变化处理。
     *
     * @param productId 产品 ID
     */
    void onReferencePriceChanged(Long productId);
}