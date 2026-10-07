package com.qiheng.erp.purchase.service.scoring;

import com.qiheng.erp.common.scoring.ProductReferencePriceChangeHandler;
import com.qiheng.erp.purchase.service.SupplierScoreRecalculateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


/**
 * 产品参考采购价变化处理器(erp-purchase 实现 erp-common 反转接口)。
 *
 * <p>由 erp-product.ProductServiceImpl.updateReferencePrice 在同一事务内同步调用,
 * 失败抛出异常触发事务回滚,保证产品参考价与评分重算的原子性。</p>
 *
 * <p>只转发产品 ID 给价格专用重算入口，避免参考价变化时扫描 180 天入库事实。</p>
 *
 * @author Li
 * @since 2026-09-25
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductReferencePriceChangeHandlerImpl implements ProductReferencePriceChangeHandler {

    private final SupplierScoreRecalculateService recalculateService;

    /**
     * 产品参考采购价变化处理。
     *
     * <p>事务传播必须为 MANDATORY,确保调用方(产品更新)已经开启了事务,
     * 否则抛出异常,避免评分重算独立提交导致数据不一致。</p>
     *
     * @param productId 参考采购价发生变化的产品 ID；为空时仅告警并跳过
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void onReferencePriceChanged(Long productId) {
        if (productId == null) {
            log.warn("onReferencePriceChanged productId 为空,跳过");
            return;
        }
        recalculateService.recalcForProductReferencePriceChange(productId);
    }
}
