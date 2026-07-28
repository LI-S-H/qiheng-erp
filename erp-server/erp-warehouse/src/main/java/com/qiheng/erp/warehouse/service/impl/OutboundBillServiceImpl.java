package com.qiheng.erp.warehouse.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.warehouse.domain.dto.OutboundBillPageDto;
import com.qiheng.erp.warehouse.domain.entity.OutboundBill;
import com.qiheng.erp.warehouse.domain.entity.OutboundBillItem;

import com.qiheng.erp.warehouse.domain.vo.OutboundBillDetailVo;
import com.qiheng.erp.warehouse.domain.vo.OutboundBillListItemVo;
import com.qiheng.erp.warehouse.domain.vo.OutboundBillPageVo;
import com.qiheng.erp.warehouse.domain.vo.OutboundBillSummaryVo;
import com.qiheng.erp.warehouse.mapper.OutboundBillMapper;
import com.qiheng.erp.warehouse.service.IOutboundBillItemService;
import com.qiheng.erp.warehouse.service.IOutboundBillService;
import com.qiheng.erp.warehouse.service.StockBillServiceHelper;
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
 * 出库单主表 服务实现类
 * </p>
 *
 * @author Li
 * @since 2026-07-26
 */
@Service
@Slf4j
public class OutboundBillServiceImpl extends ServiceImpl<OutboundBillMapper, OutboundBill> implements IOutboundBillService {

    @Autowired
    private OutboundBillMapper outboundBillMapper;

    @Autowired
    private IOutboundBillItemService outboundBillItemService;

    @Autowired
    private StockBillServiceHelper stockBillServiceHelper;

    /**
     * 分页查询出库单记录
     * @param dto 分页查询请求
     * @return 出库单分页结果
     */
    @Override
    public OutboundBillPageVo page(OutboundBillPageDto dto) {
        Page<OutboundBillListItemVo> page = dto.toPage();
        Long warehouseId;
        try {
            warehouseId = StrUtil.isNotBlank(dto.getWarehouseId())
                    ? Long.valueOf(dto.getWarehouseId()) : null;
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "仓库ID格式错误，必须为数字字符串");
        }
        MPJLambdaWrapper<OutboundBill> wrapper = buildQueryWrapper(dto, warehouseId);
        Page<OutboundBillListItemVo> result = outboundBillMapper.selectJoinPage(page, OutboundBillListItemVo.class, wrapper);
        List<OutboundBillListItemVo> records = result.getRecords();

        if (!records.isEmpty()) {
            List<Long> billIds = records.stream()
                    .map(vo -> Long.valueOf(vo.getWorkBillId()))
                    .collect(Collectors.toList());
            Map<Long, List<OutboundBillItem>> itemsByBillId = outboundBillItemService.list(
                    new LambdaQueryWrapper<OutboundBillItem>()
                            .in(OutboundBillItem::getOutboundBillId, billIds)
            ).stream().collect(Collectors.groupingBy(OutboundBillItem::getOutboundBillId));

            for (OutboundBillListItemVo vo : records) {
                Long id = Long.valueOf(vo.getWorkBillId());
                List<OutboundBillItem> items = itemsByBillId.getOrDefault(id, Collections.emptyList());
                stockBillServiceHelper.populateQuantityFields("本次出库", items, vo);
            }
        }

        OutboundBillSummaryVo summary = stockBillServiceHelper.buildSummary(records, OutboundBillSummaryVo::new);

        OutboundBillPageVo pageVo = new OutboundBillPageVo();
        pageVo.setRecords(records);
        pageVo.setTotal((int) result.getTotal());
        pageVo.setPageNum(dto.getPageNum());
        pageVo.setPageSize(dto.getPageSize());
        pageVo.setSummary(summary);
        return pageVo;
    }

    /**
     * 构建查询条件
     */
    private MPJLambdaWrapper<OutboundBill> buildQueryWrapper(OutboundBillPageDto dto, Long warehouseId) {
        return new MPJLambdaWrapper<OutboundBill>()
                .selectAs(OutboundBill::getId, OutboundBillListItemVo::getWorkBillId)
                .selectAs(OutboundBill::getOutboundNo, OutboundBillListItemVo::getBillNo)
                .select(OutboundBill::getSourceType)
                .select(OutboundBill::getSourceId)
                .select(OutboundBill::getSourceNo)
                .select(OutboundBill::getSourcePartyId)
                .select(OutboundBill::getSourcePartyName)
                .select(OutboundBill::getEntryMode)
                .select(OutboundBill::getWarehouseId)
                .select(OutboundBill::getWarehouseName)
                .select(OutboundBill::getStatus)
                .select(OutboundBill::getConfirmedById)
                .select(OutboundBill::getConfirmedByName)
                .select(OutboundBill::getConfirmedAt)
                .select(OutboundBill::getCreatedById)
                .select(OutboundBill::getCreatedByName)
                .select(OutboundBill::getResponsibleById)
                .select(OutboundBill::getResponsibleByName)
                .select(OutboundBill::getVersion)
                .select(OutboundBill::getCreateTime)
                .select(OutboundBill::getUpdateTime)
                .selectAs(OutboundBill::getOutboundType, OutboundBillListItemVo::getBillType)
                .selectCount(OutboundBillItem::getId, OutboundBillListItemVo::getItemCount)
                .leftJoin(OutboundBillItem.class, OutboundBillItem::getOutboundBillId, OutboundBill::getId)
                .like(StrUtil.isNotBlank(dto.getBillNo()), OutboundBill::getOutboundNo, dto.getBillNo())
                .like(StrUtil.isNotBlank(dto.getSourceNo()), OutboundBill::getSourceNo, dto.getSourceNo())
                .eq(warehouseId != null, OutboundBill::getWarehouseId, warehouseId)
                .eq(dto.getBillType() != null, OutboundBill::getOutboundType, dto.getBillType() != null ? dto.getBillType().name() : null)
                .eq(dto.getEntryMode() != null, OutboundBill::getEntryMode, dto.getEntryMode() != null ? dto.getEntryMode().name() : null)
                .eq(dto.getStatus() != null, OutboundBill::getStatus, dto.getStatus() != null ? dto.getStatus().name() : null)
                .groupBy(OutboundBill::getId)
                .orderByDesc(OutboundBill::getCreateTime);
    }

    /**
     * 根据ID查询出库单详情
     * @param outboundBillId 出库单ID（字符串形式）
     * @return 出库单详情
     */
    @Override
    public OutboundBillDetailVo getDetailById(String outboundBillId) {
        Long id;
        try {
            id = Long.valueOf(outboundBillId);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        OutboundBill bill = this.getById(id);
        if (bill == null) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        List<OutboundBillItem> items = outboundBillItemService.list(
                new LambdaQueryWrapper<OutboundBillItem>()
                        .eq(OutboundBillItem::getOutboundBillId, id)
                        .orderByAsc(OutboundBillItem::getId)
        );

        OutboundBillDetailVo vo = convertToDetailVo(bill);
        stockBillServiceHelper.populateQuantityFields("本次出库", items, vo);
        vo.setItems(convertToDetailItemVos(items));
        return vo;
    }

    /**
     * 出库单主表转详情VO
     */
    private OutboundBillDetailVo convertToDetailVo(OutboundBill bill) {
        if (bill.getCreateTime() == null || bill.getUpdateTime() == null) {
            OutboundBill fresh = this.getById(bill.getId());
            if (fresh != null) {
                bill = fresh;
            }
        }
        return stockBillServiceHelper.convertToDetailVo(bill, OutboundBillDetailVo::new);
    }

    /**
     * 出库单明细转详情明细VO
     *
     */
    private List<OutboundBillDetailVo.OutboundBillDetailItemVo> convertToDetailItemVos(List<OutboundBillItem> items) {
        return stockBillServiceHelper.convertToDetailItemVos(items, OutboundBillDetailVo.OutboundBillDetailItemVo::new);
    }
}
