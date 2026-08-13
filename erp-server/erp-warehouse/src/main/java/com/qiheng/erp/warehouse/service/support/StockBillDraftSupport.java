package com.qiheng.erp.warehouse.service.support;

import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.util.IdUtil;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.product.mapper.ProductMapper;
import com.qiheng.erp.warehouse.domain.warehouse.entity.Warehouse;
import com.qiheng.erp.warehouse.domain.support.StockBillDraftItem;
import com.qiheng.erp.warehouse.domain.support.StockBillTypePolicy;
import com.qiheng.erp.warehouse.mapper.WarehouseMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 手工出入库单建草稿的公共支撑。
 *
 * <p>负责可复用的仓库可用性校验、产品快照加载、数量校验和可选 ID 转换；
 * 主表与明细实体的具体组装仍由入库/出库 Service 保持，避免丢失表字段语义。</p>
 */
@Component
public class StockBillDraftSupport {

    @Autowired
    private WarehouseMapper warehouseMapper;

    @Autowired
    private ProductMapper productMapper;

    /**
     * 准备建草稿上下文，包含仓库实体和产品快照映射
     */
    public DraftContext prepare(String warehouseId,
                                Collection<? extends StockBillDraftItem> items,
                                StockBillTypePolicy typePolicy) {
        Warehouse warehouse = warehouseMapper.selectById(IdUtil.parseRequiredLongId(warehouseId, "仓库ID"));
        if (warehouse == null || warehouse.getStatus() == null || warehouse.getStatus() != 1) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "仓库不存在或已禁用");
        }
        Map<Long, Product> productMap = loadProductMap(items);
        validateQualityQuantities(items, typePolicy);
        return new DraftContext(warehouse, productMap);
    }

    /**
     * 加载产品快照映射
     */
    public Map<Long, Product> loadProductMap(Collection<? extends StockBillDraftItem> items) {
        List<Long> productIds = items.stream()
                .map(item -> IdUtil.parseRequiredLongId(item.getProductId(), "产品ID"))
                .distinct()
                .toList();
        List<Product> products = productMapper.selectByIds(productIds);
        if (products.size() != productIds.size()) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "部分产品不存在");
        }
        return products.stream().collect(Collectors.toMap(Product::getId, product -> product));
    }

    /**
     * 校验质检数量：需要质检的单据必须与本次数量相等；其他类型两个字段必须固定为 0。
     */
    public void validateQualityQuantities(Collection<? extends StockBillDraftItem> items,
                                          StockBillTypePolicy typePolicy) {
        if (!typePolicy.requiresQualityCheck()) {
            for (StockBillDraftItem item : items) {
                if (item.getQualifiedQty().compareTo(BigDecimal.ZERO) != 0
                        || item.getDefectiveQty().compareTo(BigDecimal.ZERO) != 0) {
                    throw new BizException(ErrorCode.PARAM_ERROR.getCode(),
                            "本次" + typePolicy.directionName() + "不涉及质检，合格数量和不合格数量必须为0");
                }
            }
            return;
        }
        for (StockBillDraftItem item : items) {
            BigDecimal qualitySum = item.getQualifiedQty().add(item.getDefectiveQty());
            if (qualitySum.compareTo(item.getCurrentQty()) != 0) {
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(),
                        "合格与不合格数量之和必须等于本次" + typePolicy.directionName() + "数量");
            }
        }
    }

    public record DraftContext(Warehouse warehouse, Map<Long, Product> productMap) {
    }
}