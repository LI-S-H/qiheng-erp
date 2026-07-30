package com.qiheng.erp.warehouse.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.qiheng.erp.warehouse.domain.stockbill.dto.StockBillPageDto;
import com.qiheng.erp.warehouse.domain.stockbill.entity.StockBill;
import com.qiheng.erp.warehouse.domain.stockbill.entity.StockBillItem;
import com.qiheng.erp.warehouse.domain.common.enums.SourceType;
import com.qiheng.erp.warehouse.domain.stockbill.enums.StockBillType;
import com.qiheng.erp.warehouse.domain.stockbill.vo.StockBillListItemVo;
import com.qiheng.erp.warehouse.domain.stockbill.vo.StockBillPageVo;
import com.qiheng.erp.warehouse.mapper.StockBillMapper;
import com.qiheng.erp.warehouse.service.IStockBillService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * <p>
 * 库存流水凭证主表 服务实现类
 * </p>
 *
 * @author Li
 * @since 2026-07-19
 */
@Service
@Slf4j
public class StockBillServiceImpl extends ServiceImpl<StockBillMapper, StockBill> implements IStockBillService {

    @Autowired
    private StockBillMapper stockBillMapper;

    /**
     * 分页查询库存流水记录
     * @param dto 分页查询请求
     * @return 库存流水分页结果
     */
    @Override
    public StockBillPageVo page(StockBillPageDto dto) {
        // 构建分页对象
        Page<StockBillListItemVo> page = dto.toPage();

        // warehouseId 在 DTO 中为 String（前端防精度丢失），转为 Long 用于查询
        Long warehouseId = StrUtil.isNotBlank(dto.getWarehouseId())
                ? Long.valueOf(dto.getWarehouseId()) : null;

        // 根据 sourceType 派生 billType 列表
        List<StockBillType> derivedBillTypes = deriveBillTypes(dto.getSourceType());

        // 合并前端传入的 billType 与 sourceType 派生的 billType
        List<StockBillType> billTypes;
        if (derivedBillTypes != null && !derivedBillTypes.isEmpty()) {
            // 1. 两者都传
            if (dto.getBillType() != null) {
                if (!derivedBillTypes.contains(dto.getBillType())) {
                    // 前端传入的 billType 与 sourceType 冲突，返回空结果
                    StockBillPageVo emptyPageVo = new StockBillPageVo();
                    emptyPageVo.setRecords(Collections.emptyList());
                    emptyPageVo.setTotal(0);
                    emptyPageVo.setPageNum(dto.getPageNum());
                    emptyPageVo.setPageSize(dto.getPageSize());
                    return emptyPageVo;
                }
                // 前端传入的 billType 与 sourceType 一致，返回结果
                billTypes = List.of(dto.getBillType());
            }
            // 2. 仅传 sourceType
            else {
                billTypes = derivedBillTypes;
            }
        }
        // 3. 仅传 billType
        else {
            if (dto.getBillType() != null) {
                billTypes = List.of(dto.getBillType());
            } else {
                billTypes = null;
            }
        }

        // 构建查询条件：按 stock_bill_item.bill_id 聚合 itemCount
        MPJLambdaWrapper<StockBill> wrapper = new MPJLambdaWrapper<StockBill>()
                .selectAs(StockBill::getId, StockBillListItemVo::getStockLedgerId)
                .select(StockBill::getBillNo)
                .select(StockBill::getBillType)
                .select(StockBill::getEntryMode)
                .selectAs(StockBill::getBusinessSourceId, StockBillListItemVo::getSourceId)
                .selectAs(StockBill::getBusinessSourceNo, StockBillListItemVo::getSourceNo)
                .select(StockBill::getWarehouseId)
                .select(StockBill::getWarehouseName)
                .select(StockBill::getConfirmedById)
                .select(StockBill::getConfirmedByName)
                .select(StockBill::getConfirmedAt)
                .select(StockBill::getCreateTime)
                .selectCount(StockBillItem::getId, StockBillListItemVo::getItemCount)
                .leftJoin(StockBillItem.class, StockBillItem::getBillId, StockBill::getId)
                .like(StrUtil.isNotBlank(dto.getBillNo()), StockBill::getBillNo, dto.getBillNo())
                .like(StrUtil.isNotBlank(dto.getSourceNo()), StockBill::getBusinessSourceNo, dto.getSourceNo())
                .eq(warehouseId != null, StockBill::getWarehouseId, warehouseId)
                .eq(dto.getEntryMode() != null, StockBill::getEntryMode, dto.getEntryMode())
                .in(billTypes != null, StockBill::getBillType, billTypes)
                .groupBy(StockBill::getId)
                .orderByDesc(StockBill::getCreateTime);

        Page<StockBillListItemVo> result = stockBillMapper.selectJoinPage(page, StockBillListItemVo.class, wrapper);
        List<StockBillListItemVo> records = result.getRecords();

        // 根据 billType 派生出 sourceType（sourceType 为 readOnly，不持久化，按规则派生）
        for (StockBillListItemVo vo : records) {
            vo.setSourceType(deriveSourceType(vo.getBillType()));
        }

        StockBillPageVo pageVo = new StockBillPageVo();
        pageVo.setRecords(records);
        pageVo.setTotal((int) result.getTotal());
        pageVo.setPageNum(dto.getPageNum());
        pageVo.setPageSize(dto.getPageSize());
        return pageVo;
    }

    /**
     * 根据 sourceType 派生 billType 列表
     * PURCHASE_ORDER -> PURCHASE_IN
     * SALES_ORDER -> SALES_OUT
     * PURCHASE_RETURN_ORDER -> PURCHASE_RETURN
     * SALES_RETURN_ORDER -> SALES_RETURN
     * STOCK_ADJUST -> ADJUST_IN, ADJUST_OUT
     */
    private List<StockBillType> deriveBillTypes(SourceType sourceType) {
        if (sourceType == null) {
            return null;
        }
        return switch (sourceType) {
            case PURCHASE_ORDER -> List.of(StockBillType.PURCHASE_IN);
            case SALES_ORDER -> List.of(StockBillType.SALES_OUT);
            case PURCHASE_RETURN_ORDER -> List.of(StockBillType.PURCHASE_RETURN);
            case SALES_RETURN_ORDER -> List.of(StockBillType.SALES_RETURN);
            case STOCK_ADJUST -> List.of(StockBillType.ADJUST_IN, StockBillType.ADJUST_OUT);
        };
    }

    /**
     * 根据 billType 派生出 sourceType
     * PURCHASE_IN -> PURCHASE_ORDER
     * SALES_OUT -> SALES_ORDER
     * PURCHASE_RETURN -> PURCHASE_RETURN_ORDER
     * SALES_RETURN -> SALES_RETURN_ORDER
     * ADJUST_IN / ADJUST_OUT -> STOCK_ADJUST
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