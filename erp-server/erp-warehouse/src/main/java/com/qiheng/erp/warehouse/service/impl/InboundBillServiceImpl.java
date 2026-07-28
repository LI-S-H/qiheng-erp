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
import com.qiheng.erp.product.mapper.ProductMapper;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.warehouse.domain.dto.InboundBillCreateDto;
import com.qiheng.erp.warehouse.domain.dto.InboundBillItemCreateDto;
import com.qiheng.erp.warehouse.domain.dto.StockBillUpdateDto;
import com.qiheng.erp.warehouse.domain.dto.InboundBillPageDto;
import com.qiheng.erp.warehouse.domain.dto.StockBillItemUpdateDto;
import com.qiheng.erp.warehouse.domain.entity.InboundBill;
import com.qiheng.erp.warehouse.domain.entity.InboundBillItem;
import com.qiheng.erp.warehouse.domain.entity.StockBill;
import com.qiheng.erp.warehouse.domain.entity.StockBillItem;
import com.qiheng.erp.warehouse.domain.entity.Warehouse;
import com.qiheng.erp.warehouse.domain.entity.WarehouseStock;
import com.qiheng.erp.warehouse.domain.enums.EntryMode;
import com.qiheng.erp.warehouse.domain.enums.StockBillStatus;
import com.qiheng.erp.warehouse.domain.enums.InboundType;
import com.qiheng.erp.warehouse.domain.enums.SourceType;
import com.qiheng.erp.warehouse.domain.vo.InboundBillDetailVo;
import com.qiheng.erp.warehouse.domain.vo.InboundBillListItemVo;
import com.qiheng.erp.warehouse.domain.vo.InboundBillPageVo;
import com.qiheng.erp.warehouse.domain.vo.InboundBillSummaryVo;
import com.qiheng.erp.warehouse.mapper.InboundBillMapper;
import com.qiheng.erp.warehouse.service.IInboundBillItemService;
import com.qiheng.erp.warehouse.service.IInboundBillService;
import com.qiheng.erp.warehouse.service.IStockBillItemService;
import com.qiheng.erp.warehouse.service.IStockBillService;
import com.qiheng.erp.warehouse.service.IWarehouseService;
import com.qiheng.erp.warehouse.service.IWarehouseStockService;
import com.qiheng.erp.warehouse.service.StockBillServiceHelper;
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
    private StockBillServiceHelper stockBillServiceHelper;

    @Autowired
    private IStockBillService stockBillService;

    @Autowired
    private IStockBillItemService stockBillItemService;

    @Autowired
    private IWarehouseService warehouseService;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private BillNoGenerator billNoGenerator;

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
        Long warehouseId;
        try {
            warehouseId = StrUtil.isNotBlank(dto.getWarehouseId())
                    ? Long.valueOf(dto.getWarehouseId()) : null;
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(),"仓库ID格式错误，必须为数字字符串");
        }
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
        Long id;
        try {
            id = Long.valueOf(inboundBillId);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        // 查询入库单主记录
        InboundBill bill = this.getById(id);
        if (bill == null) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        // 查询入库单明细
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
     * 新增手工入库单草稿
     * @param dto 创建请求
     * @return 入库单详情
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public InboundBillDetailVo createDraft(InboundBillCreateDto dto) {
        // 1. 校验仓库是否存在且已启用
        Long warehouseId = Long.valueOf(dto.getWarehouseId());
        Warehouse warehouse = warehouseService.getById(warehouseId);
        if (warehouse == null || warehouse.getStatus() == null || warehouse.getStatus() != 1) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "仓库不存在或已禁用");
        }

        // 2. 批量查询产品快照
        List<Long> productIds = dto.getItems().stream()
                .map(item -> Long.valueOf(item.getProductId()))
                .distinct()
                .toList();
        Map<Long, Product> productMap = getLongProductMap(productIds);

        // 3. 校验质量数量（采购入库和销售退货需要合格+不合格=本次数量）
        InboundType billType = checkQualityQty(dto);

        // 4. 确定来源类型和录入方式
        String sourceType = resolveSourceType(billType);
        String entryMode = resolveEntryMode(billType);

        // 5. 生成入库单号
        String inboundNo = billNoGenerator.nextNo("IB");

        // 6. 获取当前登录用户
        LoginUser currentUser = UserContext.getCurrentUser();
        Long currentUserId = currentUser != null ? currentUser.getUserId() : null;
        String currentUserName = currentUser != null ? currentUser.getRealName() : null;

        // 6. 组装入库单主表
        InboundBill bill = new InboundBill()
                .setInboundNo(inboundNo)
                .setInboundType(billType.name())
                .setSourceType(sourceType)
                .setSourceId(StrUtil.isNotBlank(dto.getSourceId()) ? Long.valueOf(dto.getSourceId()) : null)
                .setSourceNo(StrUtil.blankToDefault(dto.getSourceNo(), null))
                .setSourcePartyId(StrUtil.isNotBlank(dto.getSourcePartyId()) ? Long.valueOf(dto.getSourcePartyId()) : null)
                .setSourcePartyName(StrUtil.blankToDefault(dto.getSourcePartyName(), null))
                .setEntryMode(entryMode)
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

        // 7. 组装入库单明细
        List<InboundBillItem> items = new ArrayList<>();
        for (InboundBillItemCreateDto itemDto : dto.getItems()) {
            Long productId = Long.valueOf(itemDto.getProductId());
            Product product = productMap.get(productId);
            // 新增明细
            addItem(inboundNo,
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

        // 8. 查询当前库存，组装返回详情

        // 9. 组装详情VO
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
                .setSourceItemId(StrUtil.isNotBlank(sourceItemId) ? Long.valueOf(sourceItemId) : null)
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
     * 根据产品ID列表查询产品信息
     */
    private Map<Long, Product> getLongProductMap(List<Long> productIds) {
        List<Product> products = productMapper.selectByIds(productIds);
        if (products.size() != productIds.size()) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "部分产品不存在");
        }
        return products.stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
    }

    /**
     * 校验质量数量（采购入库和销售退货需要合格+不合格=本次数量）
     */
    private InboundType checkQualityQty(InboundBillCreateDto dto) {
        InboundType billType = dto.getBillType();
        if (billType == InboundType.PURCHASE_IN || billType == InboundType.SALES_RETURN) {
            for (InboundBillItemCreateDto itemDto : dto.getItems()) {
                BigDecimal qualitySum = itemDto.getQualifiedQty().add(itemDto.getDefectiveQty());
                if (qualitySum.compareTo(itemDto.getCurrentQty()) != 0) {
                    throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "合格与不合格数量之和必须等于本次入库数量");
                }
            }
        }
        return billType;
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
        Long id;
        try {
            id = Long.valueOf(inboundBillId);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "入库单ID格式错误");
        }
        InboundBill bill = this.getById(id);
        if (bill == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "入库单不存在");
        }

        // 2. 校验状态
        String status = bill.getStatus();
        boolean isDraft = StockBillStatus.DRAFT.name().equals(status);
        boolean isPendingConfirm = StockBillStatus.PENDING_CONFIRM.name().equals(status);
        if (!isDraft && !isPendingConfirm) {
            throw new BizException(ErrorCode.BILL_STATUS_INVALID.getCode(), "仅草稿和待确认单可编辑");
        }

        // 3. 乐观锁校验
        if (!bill.getVersion().equals(dto.getVersion())) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "数据已被其他人修改，请刷新后重试");
        }

        // 4. 根据状态分支处理主表字段
        InboundType billType = InboundType.valueOf(bill.getInboundType());
        if (isDraft) {
            updateBillFieldsForDraft(bill, dto);
        } else {
            updateBillFieldsForPendingConfirm(bill, dto);
        }
        if (!this.updateById(bill)) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "数据已被其他人修改，请刷新后重试");
        }

        // 5. 明细全量替换：先校验再全删全插
        List<InboundBillItem> existingItems = inboundBillItemService.list(
                new LambdaQueryWrapper<InboundBillItem>()
                        .eq(InboundBillItem::getInboundBillId, id)
        );
        if (isPendingConfirm) {
            validatePendingConfirmStructure(existingItems, dto.getItems());
        }
        replaceItems(bill, existingItems, dto.getItems(), billType);

        // 6. 查询当前库存并组装返回详情
        List<InboundBillItem> savedItems = inboundBillItemService.list(
                new LambdaQueryWrapper<InboundBillItem>()
                        .eq(InboundBillItem::getInboundBillId, id)
                        .orderByAsc(InboundBillItem::getId)
        );
        // 6.1 构建已存在明细的库存数量映射
        // 重新查询主表以获取更新后的乐观锁版本和时间
        InboundBill updatedBill = this.getById(id);
        InboundBillDetailVo vo = convertToDetailVo(updatedBill);
        stockBillServiceHelper.populateQuantityFields("本次入库", savedItems, vo);
        vo.setItems(convertToDetailItemVos(savedItems));
        return vo;
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
        Long id;
        try {
            id = Long.valueOf(inboundBillId);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "入库单ID格式错误");
        }
        InboundBill bill = this.getById(id);
        if (bill == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "入库单不存在");
        }

        // 2. 校验状态：仅允许 DRAFT
        if (!StockBillStatus.DRAFT.name().equals(bill.getStatus())) {
            throw new BizException(ErrorCode.BILL_STATUS_INVALID.getCode(), "仅草稿状态可提交");
        }

        // 3. 乐观锁校验
        if (!bill.getVersion().equals(dto.getVersion())) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "数据已被其他人修改，请刷新后重试");
        }

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
        InboundBill updatedBill = this.getById(id);
        InboundBillDetailVo vo = convertToDetailVo(updatedBill);
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
        Long id;
        try {
            id = Long.valueOf(inboundBillId);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "入库单ID格式错误");
        }
        InboundBill bill = this.getById(id);
        if (bill == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "入库单不存在");
        }

        // 2. 校验状态：允许 DRAFT 和 PENDING_CONFIRM
        String status = bill.getStatus();
        if (!StockBillStatus.DRAFT.name().equals(status)
                && !StockBillStatus.PENDING_CONFIRM.name().equals(status)) {
            throw new BizException(ErrorCode.BILL_STATUS_INVALID.getCode(), "仅草稿和待确认状态可取消");
        }

        // 3. 乐观锁校验
        if (!bill.getVersion().equals(dto.getVersion())) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "数据已被其他人修改，请刷新后重试");
        }

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
        Long id;
        try {
            id = Long.valueOf(inboundBillId);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "入库单ID格式错误");
        }
        InboundBill bill = this.getById(id);
        if (bill == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "入库单不存在");
        }

        // 2. 校验状态：仅允许 PENDING_CONFIRM
        if (!StockBillStatus.PENDING_CONFIRM.name().equals(bill.getStatus())) {
            throw new BizException(ErrorCode.BILL_STATUS_INVALID.getCode(), "仅待确认状态可确认入库");
        }

        // 3. 乐观锁校验
        if (!bill.getVersion().equals(dto.getVersion())) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "数据已被其他人修改，请刷新后重试");
        }

        // 4. 查询入库单明细
        List<InboundBillItem> items = inboundBillItemService.list(
                new LambdaQueryWrapper<InboundBillItem>()
                        .eq(InboundBillItem::getInboundBillId, id)
                        .orderByAsc(InboundBillItem::getId)
        );
        if (items.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "入库单明细为空，无法确认");
        }

        // 5. 获取当前登录用户
        LoginUser currentUser = UserContext.getCurrentUser();
        Long currentUserId = currentUser != null ? currentUser.getUserId() : null;
        String currentUserName = currentUser != null ? currentUser.getRealName() : null;
        LocalDateTime now = LocalDateTime.now();

        // 6. 生成库存流水主表
        String stockBillNo = billNoGenerator.nextNo("SL");
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

        // 7. 查询仓库信息（用于新建库存记录时填充 warehouseCode）
        Warehouse warehouse = warehouseService.getById(bill.getWarehouseId());
        String warehouseCode = (warehouse != null) ? warehouse.getWarehouseCode() : null;

        // 8. 处理每条明细：生成库存流水明细 + 更新库存 + 回写入库单明细
        List<StockBillItem> stockBillItems = new ArrayList<>();
        for (InboundBillItem item : items) {
            Long currentQty = item.getCurrentQty() != null ? item.getCurrentQty() : 0L;

            // 查询当前库存
            WarehouseStock stock = warehouseStockService.getOne(
                    new LambdaQueryWrapper<WarehouseStock>()
                            .eq(WarehouseStock::getWarehouseId, bill.getWarehouseId())
                            .eq(WarehouseStock::getProductId, item.getProductId())
            );
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

        // 9. 回写入库单明细：关联库存流水分录ID、计算剩余数量
        for (int i = 0; i < items.size(); i++) {
            InboundBillItem item = items.get(i);
            StockBillItem sbItem = stockBillItems.get(i);
            item.setStockBillItemId(sbItem.getId());
            // 计算剩余未入库数量，同时防御性校验不超过来源剩余
            if (item.getPlanQty() != null && item.getProcessedQty() != null) {
                long currentQty = item.getCurrentQty() != null ? item.getCurrentQty() : 0L;
                long remaining = item.getPlanQty() - item.getProcessedQty();
                if (currentQty > remaining) {
                    throw new BizException(ErrorCode.PARAM_ERROR.getCode(),
                            "产品 " + item.getProductName() + " 本次入库数量不能超过剩余数量 " + QtyUtil.toDecimal(remaining));
                }
                item.setPendingQty(Math.max(0L, remaining - currentQty));
            }
        }
        inboundBillItemService.updateBatchById(items);

        // 10. 更新入库单主表状态为已确认
        bill.setStatus(StockBillStatus.CONFIRMED.name());
        bill.setConfirmedById(currentUserId);
        bill.setConfirmedByName(currentUserName);
        bill.setConfirmedAt(now);
        if (!this.updateById(bill)) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "数据已被其他人修改，请刷新后重试");
        }

        // 11. 返回详情
        return getInboundBillDetailVo(id);
    }

    /**
     * DRAFT 状态更新主表字段：可修改仓库、来源信息、备注、原因
     */
    private void updateBillFieldsForDraft(InboundBill bill, StockBillItemUpdateDto dto) {
        // 更新仓库
        if (StrUtil.isNotBlank(dto.getWarehouseId())) {
            Long warehouseId = Long.valueOf(dto.getWarehouseId());
            if (!warehouseId.equals(bill.getWarehouseId())) {
                Warehouse warehouse = warehouseService.getById(warehouseId);
                if (warehouse == null || warehouse.getStatus() == null || warehouse.getStatus() != 1) {
                    throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "仓库不存在或已禁用");
                }
                bill.setWarehouseId(warehouseId).setWarehouseName(warehouse.getWarehouseName());
            }
        }

        // 更新来源信息（根据录入方式决定哪些字段可改）
        EntryMode entryMode = EntryMode.valueOf(bill.getEntryMode());
        if (entryMode == EntryMode.MANUAL_SUPPLEMENT) {
            // 人工补录：可修改来源对象和来源单号
            if (dto.getSourcePartyId() != null) {
                bill.setSourcePartyId(StrUtil.isNotBlank(dto.getSourcePartyId())
                        ? Long.valueOf(dto.getSourcePartyId()) : null);
            }
            if (dto.getSourcePartyName() != null) {
                bill.setSourcePartyName(StrUtil.blankToDefault(dto.getSourcePartyName(), null));
            }
            if (dto.getSourceNo() != null) {
                bill.setSourceNo(StrUtil.blankToDefault(dto.getSourceNo(), null));
            }
        } else if (entryMode == EntryMode.MANUAL_ADJUSTMENT) {
            // 人工调整：可修改来源仓库（sourcePartyId），但调整单号不变
            if (dto.getSourcePartyId() != null) {
                bill.setSourcePartyId(StrUtil.isNotBlank(dto.getSourcePartyId())
                        ? Long.valueOf(dto.getSourcePartyId()) : null);
            }
            if (dto.getSourcePartyName() != null) {
                bill.setSourcePartyName(StrUtil.blankToDefault(dto.getSourcePartyName(), null));
            }
        }
        // SOURCE_GENERATED：保持来源不变

        // 更新手工原因
        if (dto.getManualReason() != null) {
            bill.setManualReason(dto.getManualReason());
        }
        // 更新备注
        if (dto.getRemark() != null) {
            bill.setRemark(dto.getRemark());
        }
    }

    /**
     * PENDING_CONFIRM 状态更新主表字段：仅允许更新备注
     */
    private void updateBillFieldsForPendingConfirm(InboundBill bill, StockBillItemUpdateDto dto) {
        if (dto.getRemark() != null) {
            bill.setRemark(dto.getRemark());
        }
    }

    /**
     * PENDING_CONFIRM 结构校验：不允许增删行、不允许换产品
     */
    private void validatePendingConfirmStructure(List<InboundBillItem> existingItems,
                                                 List<StockBillUpdateDto> itemDtos) {
        if (itemDtos.size() != existingItems.size()) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "待确认状态不允许增删明细");
        }
        // 收集现有产品ID集合，与请求对比
        List<Long> existingProductIds = existingItems.stream()
                .map(InboundBillItem::getProductId)
                .sorted()
                .toList();
        List<Long> requestProductIds = itemDtos.stream()
                .map(dto -> Long.valueOf(dto.getProductId()))
                .sorted()
                .toList();
        if (!existingProductIds.equals(requestProductIds)) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "待确认状态不允许变更产品");
        }
    }

    /**
     * 明细全量替换：删除原有明细 + 重新插入请求中的全部明细
     */
    private void replaceItems(InboundBill bill,
                              List<InboundBillItem> existingItems,
                              List<StockBillUpdateDto> itemDtos,
                              InboundType billType) {
        // 校验质量数量（采购入库和销售退货）
        if (billType == InboundType.PURCHASE_IN || billType == InboundType.SALES_RETURN) {
            for (StockBillUpdateDto itemDto : itemDtos) {
                BigDecimal qualitySum = itemDto.getQualifiedQty().add(itemDto.getDefectiveQty());
                if (qualitySum.compareTo(itemDto.getCurrentQty()) != 0) {
                    throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "合格与不合格数量之和必须等于本次入库数量");
                }
            }
        }

        // 批量查询产品快照
        List<Long> productIds = itemDtos.stream()
                .map(item -> Long.valueOf(item.getProductId()))
                .distinct()
                .toList();
        Map<Long, Product> productMap = getLongProductMap(productIds);

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
            Long productId = Long.valueOf(itemDto.getProductId());
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

    /**
     * 根据入库类型确定来源类型
     */
    private String resolveSourceType(InboundType billType) {
        return switch (billType) {
            case PURCHASE_IN -> SourceType.PURCHASE_ORDER.name();
            case SALES_RETURN -> SourceType.SALES_RETURN_ORDER.name();
            case ADJUST_IN -> SourceType.STOCK_ADJUST.name();
        };
    }

    /**
     * 根据入库类型确定录入方式
     */
    private String resolveEntryMode(InboundType billType) {
        return switch (billType) {
            case PURCHASE_IN, SALES_RETURN -> EntryMode.MANUAL_SUPPLEMENT.name();
            case ADJUST_IN -> EntryMode.MANUAL_ADJUSTMENT.name();
        };
    }
}
