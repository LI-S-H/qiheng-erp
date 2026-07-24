package com.qiheng.erp.warehouse.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.qiheng.erp.warehouse.domain.dto.InboundBillPageDto;
import com.qiheng.erp.warehouse.domain.entity.InboundBill;
import com.qiheng.erp.warehouse.domain.entity.InboundBillItem;
import com.qiheng.erp.warehouse.domain.enums.EntryMode;
import com.qiheng.erp.warehouse.domain.enums.InboundBillStatus;
import com.qiheng.erp.warehouse.domain.vo.InboundBillListItemVo;
import com.qiheng.erp.warehouse.domain.vo.InboundBillPageVo;
import com.qiheng.erp.warehouse.domain.vo.InboundBillSummaryVo;
import com.qiheng.erp.warehouse.mapper.InboundBillMapper;
import com.qiheng.erp.warehouse.service.IInboundBillItemService;
import com.qiheng.erp.warehouse.service.IInboundBillService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <p>
 * 入库单主表 服务实现类
 * </p>
 *
 * @author Li
 * @since 2026-07-23
 */
@Service
@Slf4j
public class InboundBillServiceImpl extends ServiceImpl<InboundBillMapper, InboundBill> implements IInboundBillService {

    private static final long QTY_DIVISOR = 100L;

    @Autowired
    private InboundBillMapper inboundBillMapper;

    @Autowired
    private IInboundBillItemService inboundBillItemService;

    /**
     * 分页查询入库单记录
     * @param dto 分页查询请求
     * @return 入库单分页结果
     */
    @Override
    public InboundBillPageVo page(InboundBillPageDto dto) {
        // 分页查询入库单记录
        Page<InboundBillListItemVo> page = dto.toPage();
        // 处理仓库ID
        Long warehouseId = StrUtil.isNotBlank(dto.getWarehouseId())
                ? Long.valueOf(dto.getWarehouseId()) : null;
        // 构建查询条件
        MPJLambdaWrapper<InboundBill> wrapper = buildQueryWrapper(dto, warehouseId);
        // 执行查询
        Page<InboundBillListItemVo> result = inboundBillMapper.selectJoinPage(page, InboundBillListItemVo.class, wrapper);
        List<InboundBillListItemVo> records = result.getRecords();

        // 处理查询结果
        if (!records.isEmpty()) {
            // 提取入库单ID
            List<Long> billIds = records.stream()
                    .map(vo -> Long.valueOf(vo.getWorkBillId()))
                    .collect(Collectors.toList());
            // 查询入库单详情
            Map<Long, List<InboundBillItem>> itemsByBillId = inboundBillItemService.list(
                    new LambdaQueryWrapper<InboundBillItem>()
                            .in(InboundBillItem::getInboundBillId, billIds)
            ).stream().collect(Collectors.groupingBy(InboundBillItem::getInboundBillId));

            // 处理入库单详情
            for (InboundBillListItemVo vo : records) {
                Long id = Long.valueOf(vo.getWorkBillId());
                // 提取入库单详情
                List<InboundBillItem> items = itemsByBillId.getOrDefault(id, Collections.emptyList());
                // 填充数量字段
                populateQuantityFields(vo, items);
            }
        }
        
        // 基于当前分页记录计算汇总信息
        InboundBillSummaryVo summary = computeSummary(records);

        InboundBillPageVo pageVo = new InboundBillPageVo();
        pageVo.setRecords(records);
        pageVo.setTotal((int) result.getTotal());
        pageVo.setPageNum(dto.getPageNum());
        pageVo.setPageSize(dto.getPageSize());
        pageVo.setSummary(summary);
        return pageVo;
    }

    /**
     * 构建查询条件
     * @param dto 分页查询请求    
     * @param warehouseId 仓库ID
     * @return 查询条件
     */
    private MPJLambdaWrapper<InboundBill> buildQueryWrapper(InboundBillPageDto dto, Long warehouseId) {
        return new MPJLambdaWrapper<InboundBill>()
                .selectAs(InboundBill::getId, InboundBillListItemVo::getWorkBillId)
                .selectAs(InboundBill::getInboundNo, InboundBillListItemVo::getBillNo)
                .select(InboundBill::getSourceType)
                .select(InboundBill::getSourceId)
                .select(InboundBill::getSourceNo)
                .select(InboundBill::getSourcePartyId)
                .select(InboundBill::getSourcePartyName)
                .select(InboundBill::getEntryMode)
                .select(InboundBill::getWarehouseId)
                .select(InboundBill::getWarehouseName)
                .select(InboundBill::getStatus)
                .select(InboundBill::getConfirmedById)
                .select(InboundBill::getConfirmedByName)
                .select(InboundBill::getConfirmedAt)
                .select(InboundBill::getCreatedById)
                .select(InboundBill::getCreatedByName)
                .select(InboundBill::getResponsibleById)
                .select(InboundBill::getResponsibleByName)
                .select(InboundBill::getVersion)
                .select(InboundBill::getCreateTime)
                .select(InboundBill::getUpdateTime)
                .selectAs(InboundBill::getInboundType, InboundBillListItemVo::getBillType)
                .selectCount(InboundBillItem::getId, InboundBillListItemVo::getItemCount)
                .leftJoin(InboundBillItem.class, InboundBillItem::getInboundBillId, InboundBill::getId)
                .like(StrUtil.isNotBlank(dto.getBillNo()), InboundBill::getInboundNo, dto.getBillNo())
                .like(StrUtil.isNotBlank(dto.getSourceNo()), InboundBill::getSourceNo, dto.getSourceNo())
                .eq(warehouseId != null, InboundBill::getWarehouseId, warehouseId)
                .eq(dto.getBillType() != null, InboundBill::getInboundType, dto.getBillType() != null ? dto.getBillType().name() : null)
                .eq(dto.getEntryMode() != null, InboundBill::getEntryMode, dto.getEntryMode() != null ? dto.getEntryMode().name() : null)
                .eq(dto.getStatus() != null, InboundBill::getStatus, dto.getStatus() != null ? dto.getStatus().name() : null)
                .groupBy(InboundBill::getId)
                .orderByDesc(InboundBill::getCreateTime);
    }

    /**
     * 填充入库单数量字段    
     * @param vo 入库单列表项VO
     * @param items 入库单明细列表VO
     */
    private void populateQuantityFields(InboundBillListItemVo vo, List<InboundBillItem> items) {
        if (items == null || items.isEmpty()) {
            vo.setTotalCurrentQty(0);
            vo.setQuantityUnitName("");
            vo.setQuantitySummary("0");
            return;
        }
        // 计算总数量
        long sumRaw = items.stream()
                .mapToLong(item -> item.getCurrentQty() != null ? item.getCurrentQty() : 0L)
                .sum();
        long total = sumRaw / QTY_DIVISOR;
        vo.setTotalCurrentQty((int) total);

        int itemCount = items.size();
        if (itemCount >= 2) {
            // 两个及以上商品，单位字段显示"n个商品"
            vo.setQuantityUnitName(itemCount + "个商品");
        } else {
            // 1个商品，显示明细单位
            String unitName = items.getFirst().getUnitName();
            vo.setQuantityUnitName(unitName != null ? unitName : "");
        }

        // 构建数量摘要："本次入库 : 商品名称+数量, 商品名称+数量"
        StringBuilder sb = new StringBuilder("本次入库 : ");
        for (int i = 0; i < itemCount; i++) {
            // 处理每个商品的摘要
            InboundBillItem item = items.get(i);
            long qtyRaw = item.getCurrentQty() != null ? item.getCurrentQty() : 0L;
            long qty = qtyRaw / QTY_DIVISOR;
            String productName = item.getProductName() != null ? item.getProductName() : "";
            String unitName = item.getUnitName() != null ? item.getUnitName() : "";
            sb.append(productName).append(qty);
            if (StrUtil.isNotBlank(unitName)) {
                sb.append(unitName);
            }
            if (i < itemCount - 1) {
                sb.append(" , ");
            }
        }
        vo.setQuantitySummary(sb.toString());
    }
    
    /**
     * 基于当前分页记录计算入库单汇总
     * @param records 当前分页的入库单记录
     * @return 入库单汇总信息
     */
    private InboundBillSummaryVo computeSummary(List<InboundBillListItemVo> records) {
        InboundBillSummaryVo summary = new InboundBillSummaryVo();
        int sourceGeneratedCount = 0;
        int pendingCount = 0;
        int confirmedCount = 0;
        int cancelledCount = 0;

        for (InboundBillListItemVo vo : records) {
            // 统计系统自动录单数
            if (EntryMode.SOURCE_GENERATED.name().equals(vo.getEntryMode())) {
                sourceGeneratedCount++;
            }
            // 按状态统计
            String status = vo.getStatus();
            if (InboundBillStatus.PENDING_CONFIRM.name().equals(status)) {
                pendingCount++;
            } else if (InboundBillStatus.CONFIRMED.name().equals(status)) {
                confirmedCount++;
            } else if (InboundBillStatus.CANCELLED.name().equals(status)) {
                cancelledCount++;
            }
        }

        summary.setSourceGeneratedCount(sourceGeneratedCount);
        summary.setPendingCount(pendingCount);
        summary.setConfirmedCount(confirmedCount);
        summary.setCancelledCount(cancelledCount);
        return summary;
    }
}