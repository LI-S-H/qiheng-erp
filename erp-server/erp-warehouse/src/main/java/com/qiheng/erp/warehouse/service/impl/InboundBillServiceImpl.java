package com.qiheng.erp.warehouse.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.qiheng.erp.common.dto.OptimisticLockVersionDto;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.util.BillNoGenerator;
import com.qiheng.erp.common.util.IdUtil;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.warehouse.domain.inbound.dto.InboundBillCreateDto;
import com.qiheng.erp.warehouse.domain.inbound.dto.InboundBillItemCreateDto;
import com.qiheng.erp.warehouse.domain.stockbill.dto.StockBillUpdateDto;
import com.qiheng.erp.warehouse.domain.inbound.dto.InboundBillPageDto;
import com.qiheng.erp.warehouse.domain.stockbill.dto.StockBillItemUpdateDto;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBill;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBillItem;
import com.qiheng.erp.warehouse.domain.inbound.port.InboundSourceWritebackPort;
import com.qiheng.erp.warehouse.domain.stockbill.entity.StockBill;
import com.qiheng.erp.warehouse.domain.stockbill.entity.StockBillItem;
import com.qiheng.erp.warehouse.domain.warehouse.entity.Warehouse;
import com.qiheng.erp.warehouse.domain.warehousestock.entity.WarehouseStock;
import com.qiheng.erp.warehouse.domain.stockbill.enums.StockBillStatus;
import com.qiheng.erp.warehouse.domain.inbound.enums.InboundType;
import com.qiheng.erp.warehouse.domain.common.enums.EntryMode;
import com.qiheng.erp.warehouse.domain.common.enums.SourceType;
import com.qiheng.erp.warehouse.domain.inbound.vo.InboundBillDetailVo;
import com.qiheng.erp.warehouse.domain.inbound.vo.InboundBillListItemVo;
import com.qiheng.erp.warehouse.domain.inbound.vo.InboundBillPageVo;
import com.qiheng.erp.warehouse.domain.inbound.vo.InboundBillSummaryVo;
import com.qiheng.erp.warehouse.mapper.InboundBillMapper;
import com.qiheng.erp.warehouse.mapper.WarehouseMapper;
import com.qiheng.erp.warehouse.service.IInboundBillItemService;
import com.qiheng.erp.warehouse.service.IInboundBillService;
import com.qiheng.erp.warehouse.service.IStockBillItemService;
import com.qiheng.erp.warehouse.service.IStockBillService;
import com.qiheng.erp.warehouse.service.IWarehouseStockService;
import com.qiheng.erp.warehouse.service.StockBillServiceHelper;
import com.qiheng.erp.warehouse.service.support.StockBillDraftSupport;
import com.qiheng.erp.warehouse.service.support.StockBillEditingSupport;
import com.qiheng.erp.warehouse.service.support.SourceOperationLockSupport;
import com.qiheng.erp.warehouse.service.support.WarehouseStockLockSupport;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

    @Autowired
    private InboundBillMapper inboundBillMapper;

    @Autowired
    private IInboundBillItemService inboundBillItemService;

    @Autowired
    private IWarehouseStockService warehouseStockService;

    @Autowired
    private WarehouseStockLockSupport warehouseStockLockSupport;

    @Autowired
    private SourceOperationLockSupport sourceOperationLockSupport;

    @Autowired
    private StockBillServiceHelper stockBillServiceHelper;

    @Autowired
    private StockBillDraftSupport stockBillDraftSupport;

    @Autowired
    private StockBillEditingSupport stockBillEditingSupport;

    @Autowired
    private IStockBillService stockBillService;

    @Autowired
    private IStockBillItemService stockBillItemService;

    @Autowired
    private WarehouseMapper warehouseMapper;

    @Autowired
    private BillNoGenerator billNoGenerator;

    @Autowired(required = false)
    private List<InboundSourceWritebackPort> inboundSourceWritebackPorts = Collections.emptyList();

    /**
     * 分页查询入库单记录
     * @param dto 分页查询请求
     * @return 入库单分页结果
     */
    @Override
    public InboundBillPageVo page(InboundBillPageDto dto) {
        // 分页查询入库单记录
        Page<InboundBillListItemVo> page = dto.toPage();
        // 处理可选仓库 ID；非空非法值必须明确返回参数错误。
        Long warehouseId = IdUtil.parseOptionalLongId(dto.getWarehouseId(), "仓库ID");
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
                stockBillServiceHelper.populateQuantityFields("本次入库", items, vo);
            }
        }
        // 基于当前分页记录计算汇总信息
        InboundBillSummaryVo summary = stockBillServiceHelper.buildSummary(records, InboundBillSummaryVo::new);
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
     * 根据ID查询入库单详情
     * @param inboundBillId 入库单ID（字符串形式）
     * @return 入库单详情
     */
    @Override
    public InboundBillDetailVo getDetailById(String inboundBillId) {
        return getInboundBillDetailVo(stockBillEditingSupport.parseBillId(inboundBillId, "入库单"));
    }

    /**
     * 新增手工入库单草稿
     * @param dto 创建请求
     * @return 入库单详情
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public InboundBillDetailVo createDraft(InboundBillCreateDto dto) {
        // 1. 校验参数
        InboundType billType = dto.getBillType();
        // 2. 建草稿上下文
        StockBillDraftSupport.DraftContext draftContext = stockBillDraftSupport.prepare(
                dto.getWarehouseId(), dto.getItems(), billType);
        // 3. 提取上下文信息
        Long warehouseId = draftContext.warehouse().getId();
        Warehouse warehouse = draftContext.warehouse();
        Map<Long, Product> productMap = draftContext.productMap();

        // 4. 生成入库单号
        String inboundNo = billNoGenerator.nextNo(billType.billNoPrefix(),
                () -> findMaxBillNoSequence(billType.billNoPrefix()));
        String sourceNo = billType == InboundType.ADJUST_IN
                ? "ADJ" + inboundNo.substring(billType.billNoPrefix().length())
                : StrUtil.blankToDefault(dto.getSourceNo(), null);

        // 5. 获取当前登录用户
        LoginUser currentUser = UserContext.requireCurrentUser();
        Long currentUserId = currentUser.getUserId();
        String currentUserName = currentUser.getRealName();

        // 5. 组装入库单主表
        InboundBill bill = new InboundBill()
                .setInboundNo(inboundNo)
                .setInboundType(billType.name())
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

        // 6. 组装入库单明细
        List<InboundBillItem> items = new ArrayList<>();
        for (InboundBillItemCreateDto itemDto : dto.getItems()) {
            Long productId = stockBillDraftSupport.parseRequiredId(itemDto.getProductId(), "产品ID");
            Product product = productMap.get(productId);
            // 新增明细
            addItem(bill.getInboundNo(),
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
        inboundBillItemService.saveBatch(items);

        // 8. 组装详情VO
        // 重新查询明细以获取数据库生成的ID和时间
        List<InboundBillItem> savedItems = inboundBillItemService.list(
                new LambdaQueryWrapper<InboundBillItem>()
                        .eq(InboundBillItem::getInboundBillId, bill.getId())
                        .orderByAsc(InboundBillItem::getId)
        );
        InboundBillDetailVo vo = convertToDetailVo(bill);
        stockBillServiceHelper.populateQuantityFields("本次入库", savedItems, vo);
        vo.setItems(convertToDetailItemVos(savedItems));
        return vo;
    }

    /**
     * 新增入库单明细
     */
    private void addItem(String inboundNo, InboundBill bill, List<InboundBillItem> items, Long productId, Product product, String sourceItemId, BigDecimal planQty, BigDecimal currentQty, BigDecimal qualifiedQty, BigDecimal defectiveQty, String remark) {
        InboundBillItem item = new InboundBillItem()
                .setInboundBillId(bill.getId())
                .setInboundNo(inboundNo)
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
     * 编辑入库单草稿或待确认单
     * @param inboundBillId 入库单ID
     * @param dto 编辑请求
     * @return 入库单详情
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public InboundBillDetailVo updateDraft(String inboundBillId, StockBillItemUpdateDto dto) {
        // 1. 解析并查询入库单
        Long id = stockBillEditingSupport.parseBillId(inboundBillId, "入库单");
        InboundBill bill = this.getById(id);
        if (bill == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "入库单不存在");
        }

        // 2. 校验编辑状态和乐观锁版本
        StockBillEditingSupport.EditStage editStage = stockBillEditingSupport.validateEditableStage(
                bill.getStatus(), bill.getVersion(), dto.getVersion());

        // 4. 根据状态分支处理主表字段
        InboundType billType = InboundType.valueOf(bill.getInboundType());
        if (editStage == StockBillEditingSupport.EditStage.DRAFT) {
            stockBillEditingSupport.applyDraftFields(bill, dto);
        } else {
            stockBillEditingSupport.applyPendingConfirmFields(bill, dto);
        }
        if (!this.updateById(bill)) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "数据已被其他人修改，请刷新后重试");
        }

        // 5. 明细全量替换：先校验再全删全插
        List<InboundBillItem> existingItems = inboundBillItemService.list(
                new LambdaQueryWrapper<InboundBillItem>()
                        .eq(InboundBillItem::getInboundBillId, id)
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
        replaceItems(bill, existingItems, dto.getItems(), billType);

        return getInboundBillDetailVo(id);
    }

    /**
     * 提交入库单草稿为待确认
     * @param inboundBillId 入库单ID
     * @param dto 乐观锁版本号请求
     * @return 入库单详情
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public InboundBillDetailVo submitDraft(String inboundBillId, OptimisticLockVersionDto dto) {
        // 1. 解析并查询入库单
        Long id = stockBillEditingSupport.parseBillId(inboundBillId, "入库单");
        InboundBill bill = this.getById(id);
        if (bill == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "入库单不存在");
        }
        // 2. 校验状态和乐观锁版本
        stockBillEditingSupport.validateStatusAndVersion(
                bill.getStatus(), bill.getVersion(), dto.getVersion(), "仅草稿状态可提交", StockBillStatus.DRAFT);
        stockBillEditingSupport.validateBeforeSubmit(bill);
        // 4. 变更状态为待确认
        bill.setStatus(StockBillStatus.PENDING_CONFIRM.name());
        if (!this.updateById(bill)) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "数据已被其他人修改，请刷新后重试");
        }

        return getInboundBillDetailVo(id);
    }

    /**
     * 获取入库单详情
     */
    private InboundBillDetailVo getInboundBillDetailVo(Long id) {
        // 1. 查询入库单主表
        InboundBill bill = this.getById(id);
        if (bill == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "入库单不存在");
        }
        // 2. 查询明细和库存，组装返回详情
        List<InboundBillItem> items = inboundBillItemService.list(
                new LambdaQueryWrapper<InboundBillItem>()
                        .eq(InboundBillItem::getInboundBillId, id)
                        .orderByAsc(InboundBillItem::getId)
        );
        InboundBillDetailVo vo = convertToDetailVo(bill);
        stockBillServiceHelper.populateQuantityFields("本次入库", items, vo);
        vo.setItems(convertToDetailItemVos(items));
        return vo;
    }

    /**
     * 取消入库单草稿或待确认单
     * @param inboundBillId 入库单ID
     * @param dto 乐观锁版本号请求
     * @return 入库单详情
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public InboundBillDetailVo cancelBill(String inboundBillId, OptimisticLockVersionDto dto) {
        // 1. 解析并查询入库单
        Long id = stockBillEditingSupport.parseBillId(inboundBillId, "入库单");
        InboundBill bill = this.getById(id);
        if (bill == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "入库单不存在");
        }
        if (EntryMode.SOURCE_GENERATED.name().equals(bill.getEntryMode()) && bill.getSourceType() != null) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "系统生成入库单请通过来源单取消");
        }
        // 2. 校验状态和乐观锁版本
        stockBillEditingSupport.validateStatusAndVersion(bill.getStatus(), bill.getVersion(), dto.getVersion(),
                "仅草稿和待确认状态可取消", StockBillStatus.DRAFT, StockBillStatus.PENDING_CONFIRM);
        // 4. 变更状态为已取消
        bill.setStatus(StockBillStatus.CANCELLED.name());
        if (!this.updateById(bill)) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "数据已被其他人修改，请刷新后重试");
        }
        // 5. 查询明细和库存，组装返回详情
        return getInboundBillDetailVo(id);
    }

    /**
     * 确认入库单：生成库存流水并增加库存
     * @param inboundBillId 入库单ID
     * @param dto 乐观锁版本号请求
     * @return 入库单详情
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public InboundBillDetailVo confirmBill(String inboundBillId, OptimisticLockVersionDto dto) {
        // 1. 解析并查询入库单
        Long id = stockBillEditingSupport.parseBillId(inboundBillId, "入库单");
        InboundBill bill = this.getById(id);
        if (bill == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "入库单不存在");
        }
        if (SourceType.PURCHASE_ORDER.name().equals(bill.getSourceType()) && bill.getSourceId() != null) {
            sourceOperationLockSupport.acquire(bill.getSourceType(), bill.getSourceId());
            bill = this.getById(id);
            if (bill == null) {
                throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "入库单不存在");
            }
        }
        // 2. 校验状态和乐观锁版本
        stockBillEditingSupport.validateStatusAndVersion(bill.getStatus(), bill.getVersion(), dto.getVersion(),
                "仅待确认状态可确认入库", StockBillStatus.PENDING_CONFIRM);
        // 4. 查询入库单明细
        List<InboundBillItem> items = inboundBillItemService.list(
                new LambdaQueryWrapper<InboundBillItem>()
                        .eq(InboundBillItem::getInboundBillId, id)
                        .orderByAsc(InboundBillItem::getId)
        );
        if (items.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "入库单明细为空，无法确认");
        }
        // 5. 校验来源单剩余数量是否足够
        validateSourceRemainingQuantities(bill, items);
        Map<Long, WarehouseStock> lockedStocks = warehouseStockLockSupport.lockExistingStocks(
                bill.getWarehouseId(), items.stream().map(InboundBillItem::getProductId).toList());
        // 6. 获取当前登录用户
        LoginUser currentUser = UserContext.requireCurrentUser();
        Long currentUserId = currentUser.getUserId();
        String currentUserName = currentUser.getRealName();
        LocalDateTime now = LocalDateTime.now();
        // 7. 生成库存流水主表
        String stockBillNo = billNoGenerator.nextNo("SL", () -> findMaxBillNoSequence("SL"));
        StockBill stockBill = new StockBill()
                .setBillNo(stockBillNo)
                .setBillType(bill.getInboundType())
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
        // 8. 查询仓库信息（用于新建库存记录时填充 warehouseCode）
        Warehouse warehouse = warehouseMapper.selectById(bill.getWarehouseId());
        String warehouseCode = (warehouse != null) ? warehouse.getWarehouseCode() : null;
        // 9. 处理每条明细：生成库存流水明细 + 更新库存 + 回写入库单明细
        List<StockBillItem> stockBillItems = new ArrayList<>();
        for (InboundBillItem item : items) {
            Long currentQty = item.getCurrentQty() != null ? item.getCurrentQty() : 0L;
            // 查询当前库存
            WarehouseStock stock = lockedStocks.get(item.getProductId());
            Long beforeQty = (stock != null && stock.getStockQty() != null) ? stock.getStockQty() : 0L;
            Long afterQty = beforeQty + currentQty;
            // 生成库存流水分录
            StockBillItem stockBillItem = new StockBillItem()
                    .setBillId(stockBill.getId())
                    .setBusinessSourceItemId(item.getSourceItemId())
                    .setWorkBillItemId(item.getId())
                    .setProductId(item.getProductId())
                    .setProductCode(item.getProductCode())
                    .setProductName(item.getProductName())
                    .setUnitName(item.getUnitName())
                    .setQuantityPrecision(item.getQuantityPrecision())
                    .setBeforeQty(beforeQty)
                    .setChangeQty(currentQty)
                    .setAfterQty(afterQty)
                    .setQualifiedQty(item.getQualifiedQty())
                    .setDefectiveQty(item.getDefectiveQty())
                    .setRemark(item.getRemark());
            stockBillItems.add(stockBillItem);
            // 更新库存余额
            if (stock == null) {
                WarehouseStock newStock = new WarehouseStock()
                        .setWarehouseId(bill.getWarehouseId())
                        .setWarehouseCode(warehouseCode)
                        .setWarehouseName(bill.getWarehouseName())
                        .setProductId(item.getProductId())
                        .setProductCode(item.getProductCode())
                        .setProductName(item.getProductName())
                        .setUnitName(item.getUnitName())
                        .setStockQty(currentQty)
                        .setLockedQty(0L);
                try {
                    warehouseStockService.save(newStock);
                } catch (Exception e) {
                    throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "库存数据已被其他人修改，请刷新后重试");
                }
            } else {
                stock.setStockQty(afterQty);
                if (!warehouseStockService.updateById(stock)) {
                    throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "库存数据已被其他人修改，请刷新后重试");
                }
            }
        }
        // 保存库存流水明细
        stockBillItemService.saveBatch(stockBillItems);

        // 10. 回写入库单明细：关联库存流水分录ID、计算剩余数量
        Map<Long, StockBillItem> stockBillItemsByWorkBillItemId = stockBillItems.stream()
                .collect(Collectors.toMap(StockBillItem::getWorkBillItemId, item -> item));
        for (InboundBillItem item : items) {
            StockBillItem sbItem = stockBillItemsByWorkBillItemId.get(item.getId());
            if (sbItem == null || sbItem.getId() == null) {
                throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "入库单明细未生成对应库存流水");
            }
            item.setStockBillItemId(sbItem.getId());
            if (hasSourceQuantitySnapshot(bill, item)) {
                long remaining = item.getPlanQty() - item.getProcessedQty();
                item.setPendingQty(Math.max(0L, remaining - item.getCurrentQty()));
            }
        }
        inboundBillItemService.updateBatchById(items);

        // 11. 更新入库单主表状态为已确认
        bill.setStatus(StockBillStatus.CONFIRMED.name());
        bill.setConfirmedById(currentUserId);
        bill.setConfirmedByName(currentUserName);
        bill.setConfirmedAt(now);
        // 确认时填入负责人（审核人即负责人）
        bill.setResponsibleById(currentUserId);
        bill.setResponsibleByName(currentUserName);
        if (!this.updateById(bill)) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "数据已被其他人修改，请刷新后重试");
        }

        // 采购等来源模块在同一事务内回写累计数量和来源状态；未接入的来源类型暂不阻塞仓储确认。
        // TODO(销售模块完成后)：SALES_RETURN_ORDER 接通来源回写时，在库存变更前获取同一来源单操作锁。
        dispatchSourceWriteback(bill, items);

        // 12. 返回详情
        return getInboundBillDetailVo(id);
    }

    /**
     * 需要回写的来源单必须且只能有一个适配器，避免确认成功但业务状态遗漏。
     */
    private void dispatchSourceWriteback(InboundBill bill, List<InboundBillItem> items) {
        if (bill.getSourceType() == null || bill.getSourceId() == null) {
            return;
        }
        List<InboundSourceWritebackPort> matches = inboundSourceWritebackPorts.stream()
                .filter(port -> port.supports(bill.getSourceType()))
                .toList();
        if (matches.size() != 1) {
            String reason = matches.isEmpty() ? "未配置" : "配置重复";
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "入库来源单回写适配器" + reason);
        }
        matches.getFirst().onInboundConfirmed(bill, items);
    }

    /**
     * 只有已关联来源单和来源明细的工作单才校验来源剩余量；调整单和线下补录没有来源计划量。
     */
    private void validateSourceRemainingQuantities(InboundBill bill, List<InboundBillItem> items) {
        for (InboundBillItem item : items) {
            if (!hasSourceQuantitySnapshot(bill, item)) {
                continue;
            }
            long currentQty = item.getCurrentQty() != null ? item.getCurrentQty() : 0L;
            long remaining = item.getPlanQty() - item.getProcessedQty();
            if (currentQty > remaining) {
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(),
                        "产品 " + item.getProductName() + " 本次入库数量不能超过剩余数量 " + QtyUtil.toDecimal(remaining));
            }
        }
    }

    private boolean hasSourceQuantitySnapshot(InboundBill bill, InboundBillItem item) {
        return bill.getSourceId() != null && item.getSourceItemId() != null
                && item.getPlanQty() != null && item.getProcessedQty() != null;
    }

    /**
     * 入库工作单和库存流水分别位于不同业务表，Redis 序列首次初始化时按对应表查询当天最大单号。
     */
    private long findMaxBillNoSequence(String prefix) {
        List<Object> billNos = "SL".equals(prefix)
                ? stockBillService.listObjs(new LambdaQueryWrapper<StockBill>().select(StockBill::getBillNo))
                : this.listObjs(new LambdaQueryWrapper<InboundBill>().select(InboundBill::getInboundNo));
        return billNoGenerator.findMaxExistingSequence(prefix, billNos);
    }

    /**
     * 明细全量替换：删除原有明细 + 重新插入请求中的全部明细
     */
    private void replaceItems(InboundBill bill,
                              List<InboundBillItem> existingItems,
                              List<StockBillUpdateDto> itemDtos,
                              InboundType billType) {
        stockBillDraftSupport.validateQualityQuantities(itemDtos, billType);
        Map<Long, Product> productMap = stockBillDraftSupport.loadProductMap(itemDtos);
        // 全删
        if (!existingItems.isEmpty()) {
            List<Long> idsToDelete = existingItems.stream()
                    .map(InboundBillItem::getId)
                    .toList();
            inboundBillItemService.removeByIds(idsToDelete);
        }
        // 全插
        String inboundNo = bill.getInboundNo();
        List<InboundBillItem> itemsToSave = new ArrayList<>();
        for (StockBillUpdateDto itemDto : itemDtos) {
            Long productId = stockBillDraftSupport.parseRequiredId(itemDto.getProductId(), "产品ID");
            Product product = productMap.get(productId);
            addItem(inboundNo, bill, itemsToSave, productId, product,
                    itemDto.getSourceItemId(), itemDto.getPlanQty(),
                    itemDto.getCurrentQty(), itemDto.getQualifiedQty(),
                    itemDto.getDefectiveQty(), itemDto.getRemark());
        }
        if (!itemsToSave.isEmpty()) {
            inboundBillItemService.saveBatch(itemsToSave);
        }
    }


    /**
     * 入库单主表转详情VO
     */
    private InboundBillDetailVo convertToDetailVo(InboundBill bill) {
        // createTime/updateTime 由数据库生成，插入后内存对象无此值，需复查
        if (bill.getCreateTime() == null || bill.getUpdateTime() == null) {
            InboundBill fresh = this.getById(bill.getId());
            if (fresh != null) {
                bill = fresh;
            }
        }
        return stockBillServiceHelper.convertToDetailVo(bill, InboundBillDetailVo::new);
    }

    /**
     * 入库单明细转详情明细VO
     */
    private List<InboundBillDetailVo.InboundBillDetailItemVo> convertToDetailItemVos(List<InboundBillItem> items) {
        return stockBillServiceHelper.convertToDetailItemVos(items, InboundBillDetailVo.InboundBillDetailItemVo::new);
    }

}
