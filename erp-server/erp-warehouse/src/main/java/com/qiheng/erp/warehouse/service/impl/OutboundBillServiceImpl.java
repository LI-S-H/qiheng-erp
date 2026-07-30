package com.qiheng.erp.warehouse.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.qiheng.erp.common.dto.OptimisticLockVersionDto;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.util.BillNoGenerator;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.warehouse.domain.outbound.dto.OutboundBillCreateDto;
import com.qiheng.erp.warehouse.domain.outbound.dto.OutboundBillItemCreateDto;
import com.qiheng.erp.warehouse.domain.outbound.dto.OutboundBillPageDto;
import com.qiheng.erp.warehouse.domain.stockbill.dto.StockBillItemUpdateDto;
import com.qiheng.erp.warehouse.domain.stockbill.dto.StockBillUpdateDto;
import com.qiheng.erp.warehouse.domain.outbound.entity.OutboundBill;
import com.qiheng.erp.warehouse.domain.outbound.entity.OutboundBillItem;
import com.qiheng.erp.warehouse.domain.stockbill.entity.StockBill;
import com.qiheng.erp.warehouse.domain.stockbill.entity.StockBillItem;
import com.qiheng.erp.warehouse.domain.warehouse.entity.Warehouse;
import com.qiheng.erp.warehouse.domain.warehousestock.entity.WarehouseStock;
import com.qiheng.erp.warehouse.domain.common.enums.EntryMode;
import com.qiheng.erp.warehouse.domain.outbound.enums.OutboundType;
import com.qiheng.erp.warehouse.domain.common.enums.SourceType;
import com.qiheng.erp.warehouse.domain.stockbill.enums.StockBillStatus;
import com.qiheng.erp.warehouse.domain.outbound.vo.OutboundBillDetailVo;
import com.qiheng.erp.warehouse.domain.outbound.vo.OutboundBillListItemVo;
import com.qiheng.erp.warehouse.domain.outbound.vo.OutboundBillPageVo;
import com.qiheng.erp.warehouse.domain.outbound.vo.OutboundBillSummaryVo;
import com.qiheng.erp.warehouse.mapper.OutboundBillMapper;
import com.qiheng.erp.warehouse.service.IOutboundBillItemService;
import com.qiheng.erp.warehouse.service.IOutboundBillService;
import com.qiheng.erp.warehouse.service.IStockBillItemService;
import com.qiheng.erp.warehouse.service.IStockBillService;
import com.qiheng.erp.warehouse.service.IWarehouseStockService;
import com.qiheng.erp.warehouse.service.StockBillServiceHelper;
import com.qiheng.erp.warehouse.service.support.StockBillDraftSupport;
import com.qiheng.erp.warehouse.service.support.StockBillEditingSupport;
import com.qiheng.erp.warehouse.service.support.WarehouseStockReservationSupport;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private IWarehouseStockService warehouseStockService;

    @Autowired
    private IStockBillService stockBillService;

    @Autowired
    private IStockBillItemService stockBillItemService;

    @Autowired
    private StockBillServiceHelper stockBillServiceHelper;

    @Autowired
    private StockBillDraftSupport stockBillDraftSupport;

    @Autowired
    private StockBillEditingSupport stockBillEditingSupport;

    @Autowired
    private WarehouseStockReservationSupport warehouseStockReservationSupport;

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
        return getOutboundBillDetailVo(stockBillEditingSupport.parseBillId(outboundBillId, "出库单"));
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
        String outboundNo = billNoGenerator.nextNo(billType.billNoPrefix(),
                () -> findMaxBillNoSequence(billType.billNoPrefix()));
        String sourceNo = billType == OutboundType.ADJUST_OUT
                ? "ADJ" + outboundNo.substring(billType.billNoPrefix().length())
                : StrUtil.blankToDefault(dto.getSourceNo(), null);

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
                .setSourceNo(sourceNo)
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
            Long productId = stockBillDraftSupport.parseRequiredId(itemDto.getProductId(), "产品ID");
            Product product = productMap.get(productId);
            addItem(bill.getOutboundNo(),
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

        // 从建单起占用库存，避免待确认期间被其他出库业务重复使用。
        if (requiresManualStockReservation(bill)) {
            warehouseStockReservationSupport.applyLockedQtyChanges(
                    bill.getWarehouseId(), collectStoredCurrentQtyByProduct(items), false, true);
        }

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
     * 编辑出库单草稿或待确认单
     * @param outboundBillId 出库单ID
     * @param dto 编辑请求
     * @return 出库单详情
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboundBillDetailVo updateDraft(String outboundBillId, StockBillItemUpdateDto dto) {
        // 1. 解析并查询出库单
        Long id = stockBillEditingSupport.parseBillId(outboundBillId, "出库单");
        OutboundBill bill = this.getById(id);
        if (bill == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "出库单不存在");
        }

        // 2. 校验编辑状态和乐观锁版本
        StockBillEditingSupport.EditStage editStage = stockBillEditingSupport.validateEditableStage(
                bill.getStatus(), bill.getVersion(), dto.getVersion());
        Long originalWarehouseId = bill.getWarehouseId();

        // 3. 根据状态分支处理主表字段
        OutboundType billType = OutboundType.valueOf(bill.getOutboundType());
        if (editStage == StockBillEditingSupport.EditStage.DRAFT) {
            // 草稿状态编辑：
            stockBillEditingSupport.applyDraftFields(bill, dto);
        } else {
            // 待确认状态编辑：仅更新锁定库存变更
            stockBillEditingSupport.applyPendingConfirmFields(bill, dto);
        }
        if (!this.updateById(bill)) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "数据已被其他人修改，请刷新后重试");
        }

        // 4. 明细全量替换：先校验再全删全插
        List<OutboundBillItem> existingItems = outboundBillItemService.list(
                new LambdaQueryWrapper<OutboundBillItem>()
                        .eq(OutboundBillItem::getOutboundBillId, id)
        );
        if (editStage == StockBillEditingSupport.EditStage.PENDING_CONFIRM
                || EntryMode.SOURCE_GENERATED.name().equals(bill.getEntryMode())) {
            stockBillEditingSupport.validatePendingConfirmStructure(
                    existingItems.stream()
                            .map(item -> new StockBillEditingSupport.PendingConfirmItemSnapshot(
                                    item.getProductId(), item.getSourceItemId(), item.getPlanQty()))
                            .toList(),
                    dto.getItems());
        }
        // 5. 处理手动库存调整
        if (requiresManualStockReservation(bill)) {
            reconcileManualOutboundReservation(originalWarehouseId, bill.getWarehouseId(), existingItems, dto.getItems());
        }
        // 6. 替换明细
        replaceItems(bill, existingItems, dto.getItems(), billType);

        return getOutboundBillDetailVo(id);
    }

    /**
     * 提交出库单草稿为待确认单。
     * @param outboundBillId 出库单ID
     * @param dto 乐观锁版本号请求
     * @return 出库单详情
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboundBillDetailVo submitDraft(String outboundBillId, OptimisticLockVersionDto dto) {
        Long id = stockBillEditingSupport.parseBillId(outboundBillId, "出库单");
        OutboundBill bill = this.getById(id);
        if (bill == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "出库单不存在");
        }
        // 校验状态和乐观锁版本
        stockBillEditingSupport.validateStatusAndVersion(
                bill.getStatus(), bill.getVersion(), dto.getVersion(), "仅草稿状态可提交", StockBillStatus.DRAFT);
        stockBillEditingSupport.validateBeforeSubmit(bill);
        // 更新状态为待确认
        bill.setStatus(StockBillStatus.PENDING_CONFIRM.name());
        if (!this.updateById(bill)) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "数据已被其他人修改，请刷新后重试");
        }
        return getOutboundBillDetailVo(id);
    }

    /**
     * 取消出库单草稿或待确认单。
     * @param outboundBillId 出库单ID
     * @param dto 乐观锁版本号请求
     * @return 出库单详情
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboundBillDetailVo cancelBill(String outboundBillId, OptimisticLockVersionDto dto) {
        Long id = stockBillEditingSupport.parseBillId(outboundBillId, "出库单");
        OutboundBill bill = this.getById(id);
        if (bill == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "出库单不存在");
        }
        // 系统生成采购退货单的库存预占与来源单状态必须一起维护，不能由仓储通用取消接口单独处理。
        if (OutboundType.PURCHASE_RETURN.name().equals(bill.getOutboundType())
                && EntryMode.SOURCE_GENERATED.name().equals(bill.getEntryMode())) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "系统生成采购退货出库单请通过采购退货单取消");
        }
        // 校验状态和乐观锁版本
        stockBillEditingSupport.validateStatusAndVersion(bill.getStatus(), bill.getVersion(), dto.getVersion(),
                "仅草稿和待确认状态可取消", StockBillStatus.DRAFT, StockBillStatus.PENDING_CONFIRM);
        if (requiresManualStockReservation(bill)) {
            // 取消调整出库单时，释放所有预占库存
            List<OutboundBillItem> items = outboundBillItemService.list(
                    new LambdaQueryWrapper<OutboundBillItem>()
                            .eq(OutboundBillItem::getOutboundBillId, id)
            );
            // 计算释放库存数量
            Map<Long, Long> releaseQuantities = collectStoredCurrentQtyByProduct(items);
            releaseQuantities.replaceAll((productId, quantity) -> -quantity);
            warehouseStockReservationSupport.applyLockedQtyChanges(bill.getWarehouseId(), releaseQuantities, true, false);
        }
        // 更新状态为已取消
        bill.setStatus(StockBillStatus.CANCELLED.name());
        if (!this.updateById(bill)) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "数据已被其他人修改，请刷新后重试");
        }
        return getOutboundBillDetailVo(id);
    }

    /**
     * 确认出库单：校验可用库存、生成库存流水并扣减库存余额。
     * @param outboundBillId 出库单ID
     * @param dto 乐观锁版本号请求
     * @return 出库单详情
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OutboundBillDetailVo confirmBill(String outboundBillId, OptimisticLockVersionDto dto) {
        Long id = stockBillEditingSupport.parseBillId(outboundBillId, "出库单");
        OutboundBill bill = this.getById(id);
        if (bill == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "出库单不存在");
        }
        // 校验状态和乐观锁版本
        stockBillEditingSupport.validateStatusAndVersion(bill.getStatus(), bill.getVersion(), dto.getVersion(),
                "仅待确认状态可确认出库", StockBillStatus.PENDING_CONFIRM);
        // 查询出库单明细
        List<OutboundBillItem> items = outboundBillItemService.list(
                new LambdaQueryWrapper<OutboundBillItem>()
                        .eq(OutboundBillItem::getOutboundBillId, id)
                        .orderByAsc(OutboundBillItem::getId)
        );
        if (items.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "出库单明细为空，无法确认");
        }
        validateSourceRemainingQuantities(bill, items);
        // 获取当前用户信息与当前时间戳
        LoginUser currentUser = UserContext.getCurrentUser();
        Long currentUserId = currentUser != null ? currentUser.getUserId() : null;
        String currentUserName = currentUser != null ? currentUser.getRealName() : null;
        LocalDateTime now = LocalDateTime.now();
        // 生成库存流水
        StockBill stockBill = new StockBill()
                .setBillNo(billNoGenerator.nextNo("SL", () -> findMaxBillNoSequence("SL")))
                .setBillType(bill.getOutboundType())
                .setWorkBillId(String.valueOf(bill.getId()))
                .setBusinessSourceId(bill.getSourceId())
                .setBusinessSourceNo(bill.getSourceNo())
                .setEntryMode(bill.getEntryMode())
                .setWarehouseId(bill.getWarehouseId())
                .setWarehouseName(bill.getWarehouseName())
                .setConfirmedById(currentUserId)
                .setConfirmedByName(currentUserName)
                .setConfirmedAt(now)
                .setRemark(bill.getRemark());
        stockBillService.save(stockBill);
        // 所有出库单均应在建单或来源审核时完成库存预占，确认时只消费本单对应的锁定量。
        // 生成库存流水明细
        List<StockBillItem> stockBillItems = new ArrayList<>();
        for (OutboundBillItem item : items) {
            Long currentQty = item.getCurrentQty();
            if (currentQty == null || currentQty <= 0) {
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "出库数量必须大于0");
            }
            // 校验可用库存是否足够
            WarehouseStock stock = warehouseStockService.getOne(
                    new LambdaQueryWrapper<WarehouseStock>()
                            .eq(WarehouseStock::getWarehouseId, bill.getWarehouseId())
                            .eq(WarehouseStock::getProductId, item.getProductId())
            );
            Long stockQty = stock == null || stock.getStockQty() == null ? 0L : stock.getStockQty();
            Long lockedQty = stock == null || stock.getLockedQty() == null ? 0L : stock.getLockedQty();
            // 出库确认只消费前序流程已预占的锁定量，不再根据可用库存重新抢占。
            if (stock == null || stockQty < currentQty) {
                throw new BizException(ErrorCode.STOCK_INSUFFICIENT.getCode(),
                        "商品 " + item.getProductName() + " 库存不足");
            }
            if (lockedQty < currentQty) {
                throw new BizException(ErrorCode.STATUS_INVALID.getCode(),
                        "商品 " + item.getProductName() + " 锁定库存不足，无法确认出库");
            }
            long afterQty = stockQty - currentQty;
            // 生成库存流水明细
            stockBillItems.add(new StockBillItem()
                    .setBillId(stockBill.getId())
                    .setBusinessSourceItemId(item.getSourceItemId())
                    .setWorkBillItemId(item.getId())
                    .setProductId(item.getProductId())
                    .setProductCode(item.getProductCode())
                    .setProductName(item.getProductName())
                    .setUnitName(item.getUnitName())
                    .setQuantityPrecision(item.getQuantityPrecision())
                    .setBeforeQty(stockQty)
                    .setChangeQty(-currentQty)
                    .setAfterQty(afterQty)
                    .setQualifiedQty(item.getQualifiedQty())
                    .setDefectiveQty(item.getDefectiveQty())
                    .setRemark(item.getRemark()));
            // 扣减库存余额
            stock.setStockQty(afterQty);
            stock.setLockedQty(lockedQty - currentQty);
            // 更新库存余额
            if (!warehouseStockService.updateById(stock)) {
                throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "库存数据已被其他人修改，请刷新后重试");
            }
        }
        // 批量保存库存流水明细
        stockBillItemService.saveBatch(stockBillItems);
        // 更新出库单明细
        Map<Long, StockBillItem> stockBillItemsByWorkBillItemId = stockBillItems.stream()
                .collect(Collectors.toMap(StockBillItem::getWorkBillItemId, item -> item));
        for (OutboundBillItem item : items) {
            StockBillItem stockBillItem = stockBillItemsByWorkBillItemId.get(item.getId());
            if (stockBillItem == null || stockBillItem.getId() == null) {
                throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "出库单明细未生成对应库存流水");
            }
            item.setStockBillItemId(stockBillItem.getId());
            if (hasSourceQuantitySnapshot(bill, item)) {
                long remaining = item.getPlanQty() - item.getProcessedQty();
                item.setPendingQty(Math.max(0L, remaining - item.getCurrentQty()));
            }
        }
        // 批量更新出库单明细
        outboundBillItemService.updateBatchById(items);

        // TODO 接入来源业务模块后，在此按 sourceItemId 回写来源单明细的已出库数量和处理状态：
        //      采购退货出库回写采购退货单明细，销售出库回写销售订单明细；库存调整出库没有来源单，无需回写。
        //      销售出库还需同步扣减销售订单明细的锁定数量；回写必须与库存、出库单确认处于同一事务，
        //      回写前须校验 sourceItemId 确实属于 bill.sourceId，并校验来源明细的剩余数量和乐观锁版本。
        //      采购退货审核通过时，来源模块必须在生成 SOURCE_GENERATED 待确认出库单的同一事务内，
        //      按 approvedQty - processedQty 汇总预占 warehouse_stock.locked_qty；不得按本单 currentQty 预占，
        //      部分确认后生成下一张工作单也不得重复预占。APPROVED 且未处理的取消必须同步释放该剩余锁定量。
        //      还需处理部分确认状态，以及取消、编辑、重新生成工作单时的来源数量和锁定库存回滚，避免并发超额出库。

        // 更新出库单状态为已确认
        bill.setStatus(StockBillStatus.CONFIRMED.name());
        bill.setConfirmedById(currentUserId);
        bill.setConfirmedByName(currentUserName);
        bill.setConfirmedAt(now);
        if (!this.updateById(bill)) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "数据已被其他人修改，请刷新后重试");
        }
        return getOutboundBillDetailVo(id);
    }

    /**
     * 只有已关联来源单和来源明细的工作单才校验来源剩余量；调整单和线下补录没有来源计划量。
     */
    private void validateSourceRemainingQuantities(OutboundBill bill, List<OutboundBillItem> items) {
        for (OutboundBillItem item : items) {
            if (!hasSourceQuantitySnapshot(bill, item)) {
                continue;
            }
            long remaining = item.getPlanQty() - item.getProcessedQty();
            if (item.getCurrentQty() > remaining) {
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(),
                        "商品 " + item.getProductName() + " 本次出库数量不能超过剩余数量 " + QtyUtil.toDecimal(remaining));
            }
        }
    }

    private boolean hasSourceQuantitySnapshot(OutboundBill bill, OutboundBillItem item) {
        return bill.getSourceId() != null && item.getSourceItemId() != null
                && item.getPlanQty() != null && item.getProcessedQty() != null;
    }

    /**
     * 出库工作单和库存流水分别位于不同业务表，Redis 序列首次初始化时按对应表查询当天最大单号。
     */
    private long findMaxBillNoSequence(String prefix) {
        List<Object> billNos = "SL".equals(prefix)
                ? stockBillService.listObjs(new LambdaQueryWrapper<StockBill>().select(StockBill::getBillNo))
                : this.listObjs(new LambdaQueryWrapper<OutboundBill>().select(OutboundBill::getOutboundNo));
        return billNoGenerator.findMaxExistingSequence(prefix, billNos);
    }

    /**
     * 仅调整出库在创建时预占库存；销售出库和采购退货出库的锁定由其来源单据审核流程负责。
     */
    private boolean requiresManualStockReservation(OutboundBill bill) {
        return OutboundType.ADJUST_OUT.name().equals(bill.getOutboundType())
                && EntryMode.MANUAL_ADJUSTMENT.name().equals(bill.getEntryMode());
    }

    /**
     * 编辑出库单时，按旧明细和新明细的数量差额调整锁定库存；仓库变更时先释放原仓库再占用新仓库。
     */
    private void reconcileManualOutboundReservation(Long originalWarehouseId,
                                                     Long currentWarehouseId,
                                                     List<OutboundBillItem> existingItems,
                                                     List<StockBillUpdateDto> itemDtos)
    {
        // 旧明细库存映射表
        Map<Long, Long> oldQuantities = collectStoredCurrentQtyByProduct(existingItems);
        // 新明细库存映射表
        Map<Long, Long> newQuantities = collectRequestedCurrentQtyByProduct(itemDtos);
        // 仓库变更时，先释放原仓库库存
        if (!Objects.equals(originalWarehouseId, currentWarehouseId)) {
            oldQuantities.replaceAll((productId, quantity) -> -quantity);
            // 先释放原仓库库存
            warehouseStockReservationSupport.applyLockedQtyChanges(originalWarehouseId, oldQuantities, true, false);
            // 再占用新仓库库存
            warehouseStockReservationSupport.applyLockedQtyChanges(currentWarehouseId, newQuantities, false, true);
            return;
        }
        // 计算差额
        Map<Long, Long> deltas = new HashMap<>(newQuantities);
        oldQuantities.forEach((productId, oldQty) -> deltas.merge(productId, -oldQty, Long::sum));
        // 应用差额调整
        warehouseStockReservationSupport.applyLockedQtyChanges(currentWarehouseId, deltas, true, true);
    }

    /**
     * 从出库单明细中收集已存储的当前数量
     */
    private Map<Long, Long> collectStoredCurrentQtyByProduct(List<OutboundBillItem> items) {
        Map<Long, Long> quantitiesByProduct = new HashMap<>();
        for (OutboundBillItem item : items) {
            if (item.getCurrentQty() == null || item.getCurrentQty() <= 0) {
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "出库数量必须大于0");
            }
            quantitiesByProduct.merge(item.getProductId(), item.getCurrentQty(), Long::sum);
        }
        return quantitiesByProduct;
    }

    /**
     * 从出库单更新DTO中收集请求的当前数量
     */
    private Map<Long, Long> collectRequestedCurrentQtyByProduct(List<StockBillUpdateDto> itemDtos) {
        Map<Long, Long> quantitiesByProduct = new HashMap<>();
        for (StockBillUpdateDto itemDto : itemDtos) {
            Long productId = stockBillDraftSupport.parseRequiredId(itemDto.getProductId(), "产品ID");
            Long currentQty = QtyUtil.toStored(itemDto.getCurrentQty());
            if (currentQty == null || currentQty <= 0) {
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "出库数量必须大于0");
            }
            quantitiesByProduct.merge(productId, currentQty, Long::sum);
        }
        return quantitiesByProduct;
    }

    /**
     * 获取出库单详情
     */
    private OutboundBillDetailVo getOutboundBillDetailVo(Long id) {
        // 1. 查询出库单主表
        OutboundBill bill = this.getById(id);
        if (bill == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "出库单不存在");
        }
        // 2. 查询明细和库存，组装返回详情
        List<OutboundBillItem> items = outboundBillItemService.list(
                new LambdaQueryWrapper<OutboundBillItem>()
                        .eq(OutboundBillItem::getOutboundBillId, id)
                        .orderByAsc(OutboundBillItem::getId)
        );
        OutboundBillDetailVo vo = convertToDetailVo(bill);
        // 填充数量字段itemCount、totalCurrentQty、quantityUnitName、quantitySummary
        stockBillServiceHelper.populateQuantityFields("本次出库", items, vo);
        vo.setItems(convertToDetailItemVos(items));
        return vo;
    }

    /**
     * 明细全量替换：删除原有明细 + 重新插入请求中的全部明细
     */
    private void replaceItems(OutboundBill bill,
                              List<OutboundBillItem> existingItems,
                              List<StockBillUpdateDto> itemDtos,
                              OutboundType billType) {
        stockBillDraftSupport.validateQualityQuantities(itemDtos, billType);
        Map<Long, Product> productMap = stockBillDraftSupport.loadProductMap(itemDtos);

        // 全删
        if (!existingItems.isEmpty()) {
            List<Long> idsToDelete = existingItems.stream()
                    .map(OutboundBillItem::getId)
                    .toList();
            outboundBillItemService.removeByIds(idsToDelete);
        }

        // 全插
        String outboundNo = bill.getOutboundNo();
        List<OutboundBillItem> itemsToSave = new ArrayList<>();
        for (StockBillUpdateDto itemDto : itemDtos) {
            Long productId = stockBillDraftSupport.parseRequiredId(itemDto.getProductId(), "产品ID");
            Product product = productMap.get(productId);
            addItem(outboundNo, bill, itemsToSave, productId, product,
                    itemDto.getSourceItemId(), itemDto.getPlanQty(),
                    itemDto.getCurrentQty(), itemDto.getQualifiedQty(),
                    itemDto.getDefectiveQty(), itemDto.getRemark());
        }
        if (!itemsToSave.isEmpty()) {
            outboundBillItemService.saveBatch(itemsToSave);
        }
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
