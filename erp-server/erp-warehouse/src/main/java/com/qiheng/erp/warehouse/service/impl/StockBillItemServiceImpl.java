package com.qiheng.erp.warehouse.service.impl;

import com.qiheng.erp.warehouse.domain.stockbill.entity.StockBill;
import com.qiheng.erp.warehouse.domain.stockbill.entity.StockBillItem;
import com.qiheng.erp.warehouse.domain.common.enums.EntryMode;
import com.qiheng.erp.warehouse.domain.common.enums.SourceType;
import com.qiheng.erp.warehouse.domain.stockbill.enums.StockBillType;
import com.qiheng.erp.warehouse.domain.stockbill.vo.StockBillDetailsVo;
import com.qiheng.erp.warehouse.mapper.StockBillItemMapper;
import com.qiheng.erp.warehouse.mapper.StockBillMapper;
import com.qiheng.erp.warehouse.service.IStockBillItemService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 * 库存流水凭证明细表 服务实现类
 * </p>
 *
 * @author Li
 * @since 2026-07-19
 */
@Service
public class StockBillItemServiceImpl extends ServiceImpl<StockBillItemMapper, StockBillItem> implements IStockBillItemService {

    @Autowired
    private StockBillMapper stockBillMapper;

    /**
     * 根据库存流水凭证ID查询详情（包含明细列表）
     * @param stockLedgerId 库存流水凭证ID
     * @return 库存流水详情
     */
    @Override
    public StockBillDetailsVo getDetailsById(Long stockLedgerId) {
        // 1. 查询主表
        StockBill stockBill = stockBillMapper.selectById(stockLedgerId);
        if (stockBill == null) {
            return null;
        }

        // 2. 查询明细列表
        List<StockBillItem> items = this.lambdaQuery()
                .eq(StockBillItem::getBillId, stockLedgerId)
                .orderByAsc(StockBillItem::getId)
                .list();

        StockBillType billType = StockBillType.valueOf(stockBill.getBillType());

        // 3. 组装 VO
        StockBillDetailsVo vo = new StockBillDetailsVo();
        vo.setStockLedgerId(stockBill.getId());
        vo.setBillNo(stockBill.getBillNo());
        vo.setBillType(billType);
        vo.setEntryMode(EntryMode.valueOf(stockBill.getEntryMode()));
        vo.setSourceType(deriveSourceType(billType));
        vo.setSourceId(stockBill.getBusinessSourceId());
        vo.setSourceNo(stockBill.getBusinessSourceNo());
        vo.setWarehouseId(stockBill.getWarehouseId());
        vo.setWarehouseName(stockBill.getWarehouseName());
        vo.setConfirmedById(stockBill.getConfirmedById());
        vo.setConfirmedByName(stockBill.getConfirmedByName());
        vo.setConfirmedAt(stockBill.getConfirmedAt());
        vo.setCreateTime(stockBill.getCreateTime());

        // 4. 转换明细列表
        List<StockBillDetailsVo.StockBillDetailItemVo> detailItems = items.stream()
                .map(this::convertToDetailItemVo)
                .toList();
        vo.setItems(detailItems);

        return vo;
    }

    /**
     * 将库存流水明细实体转换为VO
     * @param item 库存流水明细实体
     * @return 库存流水明细VO
     */
    private StockBillDetailsVo.StockBillDetailItemVo convertToDetailItemVo(StockBillItem item) {
        StockBillDetailsVo.StockBillDetailItemVo vo = new StockBillDetailsVo.StockBillDetailItemVo();
        vo.setStockLedgerItemId(item.getId());
        vo.setStockLedgerId(item.getBillId());
        vo.setProductId(item.getProductId());
        vo.setProductCode(item.getProductCode());
        vo.setProductName(item.getProductName());
        vo.setUnitName(item.getUnitName());
        vo.setBeforeQty(convertQuantity(item.getBeforeQty()));
        vo.setChangeQty(convertQuantity(item.getChangeQty()));
        vo.setAfterQty(convertQuantity(item.getAfterQty()));
        vo.setQualifiedQty(convertQuantity(item.getQualifiedQty()));
        vo.setDefectiveQty(convertQuantity(item.getDefectiveQty()));
        vo.setRemark(item.getRemark());
        return vo;
    }

    /**
     * 将按100倍整数存储的数量转换为实际数量
     * @param storedQty 存储的数量（按100倍整数）
     */
    private Double convertQuantity(Long storedQty) {
        if (storedQty == null) {
            return null;
        }
        if (storedQty == 0L) {
            return 0d;
        }
        return storedQty / 100.0;
    }


    /**
     * 根据 billType 派生出 sourceType
     * @param billType 库存流水类型
     * @return 对应的 sourceType
     */
    private SourceType deriveSourceType(StockBillType billType) {
        if (billType == null) {
            return null;
        }
        return switch (billType) {
            case PURCHASE_IN -> SourceType.PURCHASE_ORDER;
            case SALES_OUT -> SourceType.SALES_ORDER;
            case PURCHASE_RETURN -> SourceType.PURCHASE_RETURN_ORDER;
            case SALES_RETURN -> SourceType.SALES_RETURN_ORDER;
            case ADJUST_IN, ADJUST_OUT -> SourceType.STOCK_ADJUST;
        };
    }

}