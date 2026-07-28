package com.qiheng.erp.warehouse.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.util.BillNoGenerator;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.warehouse.domain.dto.OutboundBillCreateDto;
import com.qiheng.erp.warehouse.domain.dto.OutboundBillItemCreateDto;
import com.qiheng.erp.warehouse.domain.dto.OutboundBillPageDto;
import com.qiheng.erp.warehouse.domain.entity.OutboundBill;
import com.qiheng.erp.warehouse.domain.entity.OutboundBillItem;
import com.qiheng.erp.warehouse.domain.entity.Warehouse;
import com.qiheng.erp.warehouse.domain.enums.OutboundType;
import com.qiheng.erp.warehouse.domain.enums.StockBillStatus;
import com.qiheng.erp.warehouse.domain.vo.OutboundBillDetailVo;
import com.qiheng.erp.warehouse.domain.vo.OutboundBillListItemVo;
import com.qiheng.erp.warehouse.domain.vo.OutboundBillPageVo;
import com.qiheng.erp.warehouse.domain.vo.OutboundBillSummaryVo;
import com.qiheng.erp.warehouse.mapper.OutboundBillMapper;
import com.qiheng.erp.warehouse.service.IOutboundBillItemService;
import com.qiheng.erp.warehouse.service.IOutboundBillService;
import com.qiheng.erp.warehouse.service.StockBillServiceHelper;
import com.qiheng.erp.warehouse.service.support.StockBillDraftSupport;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
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

    @Autowired
    private StockBillDraftSupport stockBillDraftSupport;

    @Autowired
    private BillNoGenerator billNoGenerator;

    /**
     * 分页查询出库单记录
     * @param dto 分页查询请求
     * @return 出库单分页结果
     */
    @Override
    public OutboundBillPageVo page(OutboundBillPageDto dto) {
        Page<OutboundBillListItemVo> page = dto.toPage();
        Long warehouseId;
        // 构建查询条件包装器
        try {
            warehouseId = StrUtil.isNotBlank(dto.getWarehouseId())
                    ? Long.valueOf(dto.getWarehouseId()) : null;
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "仓库ID格式错误，必须为数字字符串");
        }
        MPJLambdaWrapper<OutboundBill> wrapper = buildQueryWrapper(dto, warehouseId);
        Page<OutboundBillListItemVo> result = outboundBillMapper.selectJoinPage(page, OutboundBillListItemVo.class, wrapper);
        List<OutboundBillListItemVo> records = result.getRecords();
        // 加载出库单明细
        if (!records.isEmpty()) {
            // 构建出库单ID列表
            List<Long> billIds = records.stream()
                    .map(vo -> Long.valueOf(vo.getWorkBillId()))
                    .collect(Collectors.toList());
            Map<Long, List<OutboundBillItem>> itemsByBillId = outboundBillItemService.list(
                    new LambdaQueryWrapper<OutboundBillItem>()
                            .in(OutboundBillItem::getOutboundBillId, billIds)
            ).stream().collect(Collectors.groupingBy(OutboundBillItem::getOutboundBillId));
            // 填充出库单明细
            for (OutboundBillListItemVo vo : records) {
                Long id = Long.valueOf(vo.getWorkBillId());
                List<OutboundBillItem> items = itemsByBillId.getOrDefault(id, Collections.emptyList());
                stockBillServiceHelper.populateQuantityFields("本次出库", items, vo);
            }
        }
        // 构建分页汇总信息
        OutboundBillSummaryVo summary = stockBillServiceHelper.buildSummary(records, OutboundBillSummaryVo::new);
        // 构建分页结果
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
     * 新增手工出库单草稿
     * @param dto 创建请求
     * @return 出库单详情
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboundBillDetailVo createDraft(OutboundBillCreateDto dto) {
        // 1. 校验参数
        OutboundType billType = dto.getBillType();
        // 2. 建草稿上下文
        StockBillDraftSupport.DraftContext draftContext = stockBillDraftSupport.prepare(
                dto.getWarehouseId(), dto.getItems(), billType);
        // 3. 提取上下文信息
        Long warehouseId = draftContext.warehouse().getId();
        Warehouse warehouse = draftContext.warehouse();
        Map<Long, Product> productMap = draftContext.productMap();

        // 4. 生成出库单号
        String outboundNo = billNoGenerator.nextNo(billType.billNoPrefix());

        // 5. 获取当前登录用户
        LoginUser currentUser = UserContext.getCurrentUser();
        Long currentUserId = currentUser != null ? currentUser.getUserId() : null;
        String currentUserName = currentUser != null ? currentUser.getRealName() : null;

        // 6. 组装出库单主表
        OutboundBill bill = new OutboundBill()
                .setOutboundNo(outboundNo)
                .setOutboundType(billType.name())
                .setSourceType(billType.sourceType().name())
                .setSourceId(stockBillDraftSupport.toNullableLong(dto.getSourceId()))
                .setSourceNo(StrUtil.blankToDefault(dto.getSourceNo(), null))
                .setSourcePartyId(stockBillDraftSupport.toNullableLong(dto.getSourcePartyId()))
                .setSourcePartyName(StrUtil.blankToDefault(dto.getSourcePartyName(), null))
                .setEntryMode(billType.entryMode().name())
                .setWarehouseId(warehouseId)
                .setWarehouseName(warehouse.getWarehouseName())
                .setStatus(StockBillStatus.DRAFT.name())
                .setCreatedById(currentUserId)
                .setCreatedByName(currentUserName)
                .setResponsibleById(currentUserId)
                .setResponsibleByName(currentUserName)
                .setManualReason(dto.getManualReason())
                .setRemark(dto.getRemark());
        this.save(bill);

        // 7. 组装出库单明细
        List<OutboundBillItem> items = new ArrayList<>();
        for (OutboundBillItemCreateDto itemDto : dto.getItems()) {
            Long productId = Long.valueOf(itemDto.getProductId());
            Product product = productMap.get(productId);
            addItem(outboundNo,
                    bill,
                    items,
                    productId,
                    product,
                    itemDto.getSourceItemId(),
                    itemDto.getPlanQty(),
                    itemDto.getCurrentQty(),
                    itemDto.getQualifiedQty(),
                    itemDto.getDefectiveQty(),
                    itemDto.getRemark());
        }
        outboundBillItemService.saveBatch(items);

        // 8. 组装详情VO
        // 重新查询明细以获取数据库生成的ID和时间
        List<OutboundBillItem> savedItems = outboundBillItemService.list(
                new LambdaQueryWrapper<OutboundBillItem>()
                        .eq(OutboundBillItem::getOutboundBillId, bill.getId())
                        .orderByAsc(OutboundBillItem::getId)
        );
        OutboundBillDetailVo vo = convertToDetailVo(bill);
        stockBillServiceHelper.populateQuantityFields("本次出库", savedItems, vo);
        vo.setItems(convertToDetailItemVos(savedItems));
        return vo;
    }

    /**
     * 新增出库单明细
     */
    private void addItem(String outboundNo, OutboundBill bill, List<OutboundBillItem> items, Long productId, Product product, String sourceItemId, BigDecimal planQty, BigDecimal currentQty, BigDecimal qualifiedQty, BigDecimal defectiveQty, String remark) {
        OutboundBillItem item = new OutboundBillItem()
                .setOutboundBillId(bill.getId())
                .setOutboundNo(outboundNo)
                .setSourceItemId(stockBillDraftSupport.toNullableLong(sourceItemId))
                .setProductId(productId)
                .setProductCode(product.getProductCode())
                .setProductName(product.getProductName())
                .setUnitName(product.getUnitName())
                .setQuantityPrecision(product.getQuantityPrecision())
                .setPlanQty(planQty != null && planQty.compareTo(BigDecimal.ZERO) > 0 ? QtyUtil.toStored(planQty) : null)
                .setProcessedQty(null)
                .setCurrentQty(QtyUtil.toStored(currentQty))
                .setPendingQty(null)
                .setQualifiedQty(QtyUtil.toStored(qualifiedQty))
                .setDefectiveQty(QtyUtil.toStored(defectiveQty))
                .setRemark(remark);
        items.add(item);
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
