package com.qiheng.erp.purchase.service.impl;

import lombok.extern.slf4j.Slf4j;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.util.BillNoGenerator;
import com.qiheng.erp.common.util.IdUtil;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.product.mapper.ProductMapper;
import com.qiheng.erp.purchase.domain.purchaseorder.dto.PurchaseOrderCreateDto;
import com.qiheng.erp.purchase.domain.purchaseorder.dto.PurchaseOrderItemDto;
import com.qiheng.erp.purchase.domain.purchaseorder.dto.PurchaseOrderPageDto;
import com.qiheng.erp.purchase.domain.purchaseorder.dto.PurchaseOrderUpdateDto;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrderItem;
import com.qiheng.erp.purchase.domain.purchaseorder.enums.PurchaseOrderStatus;
import com.qiheng.erp.purchase.domain.purchaseorder.vo.PurchaseOrderDetailVo;
import com.qiheng.erp.purchase.domain.purchaseorder.vo.PurchaseOrderFulfillmentSummaryVo;
import com.qiheng.erp.purchase.domain.purchaseorder.vo.PurchaseOrderItemVo;
import com.qiheng.erp.purchase.domain.purchaseorder.vo.PurchaseOrderTimelineItemVo;
import com.qiheng.erp.purchase.domain.purchaseorder.vo.PurchaseOrderVo;
import com.qiheng.erp.purchase.domain.supplier.entity.Supplier;
import com.qiheng.erp.purchase.domain.supplierproduct.entity.SupplierProduct;
import com.qiheng.erp.purchase.mapper.PurchaseOrderItemMapper;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.purchase.mapper.SupplierMapper;
import com.qiheng.erp.purchase.mapper.SupplierProductMapper;
import com.qiheng.erp.purchase.service.IPurchaseOrderItemService;
import com.qiheng.erp.purchase.service.IPurchaseOrderService;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.warehouse.domain.common.enums.EntryMode;
import com.qiheng.erp.warehouse.domain.common.enums.SourceType;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBill;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBillItem;
import com.qiheng.erp.warehouse.domain.inbound.enums.InboundType;
import com.qiheng.erp.warehouse.domain.stockbill.enums.StockBillStatus;
import com.qiheng.erp.warehouse.domain.warehouse.entity.Warehouse;
import com.qiheng.erp.warehouse.mapper.InboundBillItemMapper;
import com.qiheng.erp.warehouse.mapper.InboundBillMapper;
import com.qiheng.erp.warehouse.mapper.WarehouseMapper;
import com.qiheng.erp.warehouse.service.support.SourceOperationLockSupport;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <p>
 * 采购订单主表 服务实现类
 * </p>
 *
 * @author Li
 * @since 2026-07-31
 */
@Slf4j
@Service
public class PurchaseOrderServiceImpl extends ServiceImpl<PurchaseOrderMapper, PurchaseOrder> implements IPurchaseOrderService {

    @Autowired
    private PurchaseOrderMapper purchaseOrderMapper;

    @Autowired
    private PurchaseOrderItemMapper purchaseOrderItemMapper;

    @Autowired
    private IPurchaseOrderItemService purchaseOrderItemService;

    @Autowired
    private SupplierMapper supplierMapper;

    @Autowired
    private WarehouseMapper warehouseMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private SupplierProductMapper supplierProductMapper;

    @Autowired
    private InboundBillMapper inboundBillMapper;

    @Autowired
    private InboundBillItemMapper inboundBillItemMapper;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private SourceOperationLockSupport sourceOperationLockSupport;

    @Autowired
    private BillNoGenerator billNoGenerator;

    /**
     * 采购订单分页查询
     * @param dto 分页查询参数DTO
     * @return 分页查询结果VO
     */
    @Override
    public PageResult<PurchaseOrderVo> page(PurchaseOrderPageDto dto) {
        Long supplierId = IdUtil.parseOptionalLongId(dto.getSupplierId(), "供应商ID");
        Long warehouseId = IdUtil.parseOptionalLongId(dto.getWarehouseId(), "仓库ID");
        String statusName = dto.getStatus() != null ? dto.getStatus().name() : null;
        // 构建查询条件
        LambdaQueryWrapper<PurchaseOrder> wrapper = new LambdaQueryWrapper<PurchaseOrder>()
                .like(StrUtil.isNotBlank(dto.getPurchaseNo()), PurchaseOrder::getPurchaseNo, dto.getPurchaseNo())
                .eq(supplierId != null, PurchaseOrder::getSupplierId, supplierId)
                .eq(warehouseId != null, PurchaseOrder::getWarehouseId, warehouseId)
                .eq(statusName != null, PurchaseOrder::getStatus, statusName)
                .orderByDesc(PurchaseOrder::getCreateTime);
        // 执行分页查询
        Page<PurchaseOrder> page = this.page(dto.toPage(), wrapper);
        return PageResult.of(
                page.getRecords().stream().map(this::toVo).toList(),
                (int) page.getTotal(),
                (int) page.getCurrent(),
                (int) page.getSize()
        );
    }

    /**
     * 实体转列表VO
     * @param entity 采购订单实体
     * @return 列表VO
     */
    private PurchaseOrderVo toVo(PurchaseOrder entity) {
        PurchaseOrderVo vo = new PurchaseOrderVo();
        BeanUtil.copyProperties(entity, vo, "id");
        vo.setPurchaseOrderId(entity.getId());
        vo.setTotalAmount(QtyUtil.toDecimal(vo.getTotalAmount()));
        return vo;
    }

    /**
     * 获取采购订单详情（主表 + 明细列表）
     * @param purchaseOrderId 采购订单ID
     * @return 采购订单详情VO
     */
    @Override
    public PurchaseOrderDetailVo getDetail(Long purchaseOrderId) {
        PurchaseOrder order = purchaseOrderMapper.selectById(purchaseOrderId);
        if (order == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
        // 数量精度必须读取下单时的明细快照，不能随产品档案变更而漂移。
        MPJLambdaWrapper<PurchaseOrderItem> wrapper = new MPJLambdaWrapper<PurchaseOrderItem>()
                .selectAs(PurchaseOrderItem::getId, PurchaseOrderItemVo::getPurchaseOrderItemId)
                .select(PurchaseOrderItem::getPurchaseOrderId)
                .select(PurchaseOrderItem::getPurchaseNo)
                .select(PurchaseOrderItem::getSupplierProductId)
                .select(PurchaseOrderItem::getProductId)
                .select(PurchaseOrderItem::getProductCode)
                .select(PurchaseOrderItem::getProductName)
                .select(PurchaseOrderItem::getUnitName)
                .select(PurchaseOrderItem::getQuantityPrecision)
                .select(PurchaseOrderItem::getQuantity)
                .select(PurchaseOrderItem::getInboundQty)
                .select(PurchaseOrderItem::getUnitPrice)
                .select(PurchaseOrderItem::getTotalAmount)
                .select(PurchaseOrderItem::getSelectedSupplierScore)
                .select(PurchaseOrderItem::getRemark)
                .eq(PurchaseOrderItem::getPurchaseOrderId, purchaseOrderId);
        List<PurchaseOrderItemVo> items = purchaseOrderItemMapper.selectJoinList(PurchaseOrderItemVo.class, wrapper);
        items.forEach(this::convertItemStoredValues);
        // 组装详情VO
        PurchaseOrderDetailVo detail = new PurchaseOrderDetailVo();
        BeanUtil.copyProperties(toVo(order), detail);
        detail.setItems(items);
        detail.setFulfillmentSummary(buildFulfillmentSummary(order, items));
        detail.setTimeline(buildTimeline(order));
        return detail;
    }

    /**
     * 新增采购订单草稿
     * @param dto 新增采购订单请求DTO
     * @return 采购订单详情VO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PurchaseOrderDetailVo createDraft(PurchaseOrderCreateDto dto) {
        Long supplierId = IdUtil.parseRequiredLongId(dto.getSupplierId(), "供应商ID");
        Long warehouseId = IdUtil.parseRequiredLongId(dto.getWarehouseId(), "仓库ID");
        // 查询供应商快照
        Supplier supplier = supplierMapper.selectById(supplierId);
        if (supplier == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "供应商不存在");
        }
        // 查询仓库快照
        Warehouse warehouse = warehouseMapper.selectById(warehouseId);
        if (warehouse == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "仓库不存在");
        }
        // 批量查询产品快照，避免循环查库
        List<Long> productIds = dto.getItems().stream()
                .map(item -> IdUtil.parseRequiredLongId(item.getProductId(), "产品ID"))
                .toList();
        Map<Long, Product> productMap = productMapper.selectByIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
        // 获取当前登录用户
        LoginUser loginUser = UserContext.requireCurrentUser();
        // 生成采购单号
        String purchaseNo = billNoGenerator.nextNo("PO");
        // 构建明细并累加总金额
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<PurchaseOrderItem> items = new ArrayList<>();
        for (PurchaseOrderItemDto itemDto : dto.getItems()) {
            Long productId = IdUtil.parseRequiredLongId(itemDto.getProductId(), "产品ID");
            Product product = productMap.get(productId);
            if (product == null) {
                throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "产品不存在: " + itemDto.getProductId());
            }
            validateQuantityPrecision(itemDto, product.getQuantityPrecision());
            BigDecimal unitPrice = itemDto.getUnitPrice();
            BigDecimal lineAmount = itemDto.getQuantity().multiply(unitPrice)
                    .setScale(2, RoundingMode.HALF_UP);
            totalAmount = totalAmount.add(lineAmount);
            PurchaseOrderItem item = new PurchaseOrderItem();
            item.setPurchaseOrderId(null);
            item.setPurchaseNo(purchaseNo);
            item.setSupplierProductId(IdUtil.parseRequiredLongId(itemDto.getSupplierProductId(), "供应商供货产品ID"));
            item.setProductId(productId);
            item.setProductCode(product.getProductCode());
            item.setProductName(product.getProductName());
            item.setUnitName(product.getUnitName());
            item.setQuantityPrecision(product.getQuantityPrecision());
            item.setQuantity(QtyUtil.toStored(itemDto.getQuantity()));
            item.setInboundQty(0L);
            item.setUnitPrice(QtyUtil.toStored(unitPrice));
            item.setTotalAmount(QtyUtil.toStored(lineAmount));
            item.setSelectedSupplierScore(QtyUtil.toStoredInt(itemDto.getSelectedSupplierScore()));
            item.setRemark(itemDto.getRemark());
            items.add(item);
        }
        // 构建并插入主表
        PurchaseOrder order = new PurchaseOrder();
        order.setPurchaseNo(purchaseNo);
        order.setSupplierId(supplierId);
        order.setSupplierCode(supplier.getSupplierCode());
        order.setSupplierName(supplier.getSupplierName());
        order.setWarehouseId(warehouseId);
        order.setWarehouseName(warehouse.getWarehouseName());
        order.setStatus(PurchaseOrderStatus.DRAFT.name());
        order.setExpectedArrivalDate(dto.getExpectedArrivalDate());
        order.setCreatedById(loginUser.getUserId());
        order.setCreatedByName(loginUser.getRealName());
        order.setTotalAmount(QtyUtil.toStored(totalAmount));
        order.setRemark(dto.getRemark());
        purchaseOrderMapper.insert(order);
        // 回填明细的订单ID并批量插入
        items.forEach(item -> item.setPurchaseOrderId(order.getId()));
        purchaseOrderItemService.saveBatch(items);
        return getDetail(order.getId());
    }

    /**
     * 编辑采购订单（全量替换明细，重算总金额）
     * @param purchaseOrderId 采购订单ID
     * @param dto 编辑采购订单请求DTO
     * @return 采购订单详情VO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PurchaseOrderDetailVo update(Long purchaseOrderId, PurchaseOrderUpdateDto dto) {
        Long supplierId = IdUtil.parseRequiredLongId(dto.getSupplierId(), "供应商ID");
        Long warehouseId = IdUtil.parseRequiredLongId(dto.getWarehouseId(), "仓库ID");
        // 查询原订单
        PurchaseOrder order = purchaseOrderMapper.selectById(purchaseOrderId);
        if (order == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
        // 校验状态：仅 DRAFT 和 SUBMITTED（APPROVED 之前）可编辑
        String status = order.getStatus();
        boolean isSubmitted = PurchaseOrderStatus.SUBMITTED.name().equals(status);
        if (!PurchaseOrderStatus.DRAFT.name().equals(status) && !isSubmitted) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "当前状态不允许编辑");
        }
        // SUBMITTED 状态编辑时预计到货日期必须非空
        if (isSubmitted && dto.getExpectedArrivalDate() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "已提交状态编辑时预计到货日期不能为空");
        }
        // 校验乐观锁版本
        validateVersion(order, dto.getVersion());
        // 校验供应商、仓库
        Supplier supplier = supplierMapper.selectById(supplierId);
        if (supplier == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "供应商不存在");
        }
        Warehouse warehouse = warehouseMapper.selectById(warehouseId);
        if (warehouse == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "仓库不存在");
        }
        // 批量查询产品
        Map<Long, Product> productMap = loadProducts(dto.getItems());
        // 校验供货关系
        Map<Long, Long> supplierProductToProduct = new HashMap<>();
        for (PurchaseOrderItemDto itemDto : dto.getItems()) {
            Long spId = IdUtil.parseRequiredLongId(itemDto.getSupplierProductId(), "供应商供货产品ID");
            Long productId = IdUtil.parseRequiredLongId(itemDto.getProductId(), "产品ID");
            supplierProductToProduct.put(spId, productId);
        }
        validateSupplierProductRelations(supplierId, supplierProductToProduct);
        // 明细全量替换：先全删再全插（参考入库单 replaceItems）
        // 编辑只在 APPROVED 之前进行，此时 inboundQty 必为 0，无需保留
        List<PurchaseOrderItem> existingItems = purchaseOrderItemService.list(
                new LambdaQueryWrapper<PurchaseOrderItem>()
                        .eq(PurchaseOrderItem::getPurchaseOrderId, purchaseOrderId));
        Map<Long, PurchaseOrderItem> existingItemById = existingItems.stream()
                .collect(Collectors.toMap(PurchaseOrderItem::getId, item -> item));
        if (!existingItems.isEmpty()) {
            List<Long> idsToDelete = existingItems.stream()
                    .map(PurchaseOrderItem::getId)
                    .toList();
            purchaseOrderItemService.removeByIds(idsToDelete);
        }
        // 构建新明细并累加总金额
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<PurchaseOrderItem> itemsToSave = new ArrayList<>();
        for (PurchaseOrderItemDto itemDto : dto.getItems()) {
            Long productId = IdUtil.parseRequiredLongId(itemDto.getProductId(), "产品ID");
            Product product = productMap.get(productId);
            if (product == null) {
                throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "产品不存在: " + itemDto.getProductId());
            }
            Integer quantityPrecision = resolveUpdateQuantityPrecision(itemDto, productId, product.getQuantityPrecision(), existingItemById);
            // 单价使用前端提交的业务值
            BigDecimal unitPrice = itemDto.getUnitPrice();
            BigDecimal lineAmount = itemDto.getQuantity().multiply(unitPrice)
                    .setScale(2, RoundingMode.HALF_UP);
            totalAmount = totalAmount.add(lineAmount);
            PurchaseOrderItem item = new PurchaseOrderItem();
            item.setPurchaseOrderId(purchaseOrderId);
            item.setPurchaseNo(order.getPurchaseNo());
            item.setSupplierProductId(IdUtil.parseRequiredLongId(itemDto.getSupplierProductId(), "供应商供货产品ID"));
            item.setProductId(productId);
            item.setProductCode(product.getProductCode());
            item.setProductName(product.getProductName());
            item.setUnitName(product.getUnitName());
            item.setQuantityPrecision(quantityPrecision);
            item.setQuantity(QtyUtil.toStored(itemDto.getQuantity()));
            item.setInboundQty(0L);
            item.setUnitPrice(QtyUtil.toStored(unitPrice));
            item.setTotalAmount(QtyUtil.toStored(lineAmount));
            item.setSelectedSupplierScore(QtyUtil.toStoredInt(itemDto.getSelectedSupplierScore()));
            item.setRemark(itemDto.getRemark());
            itemsToSave.add(item);
        }
        if (!itemsToSave.isEmpty()) {
            purchaseOrderItemService.saveBatch(itemsToSave);
        }
        // 更新主表（乐观锁）
        PurchaseOrder update = new PurchaseOrder();
        update.setId(purchaseOrderId);
        update.setSupplierId(supplierId);
        update.setSupplierCode(supplier.getSupplierCode());
        update.setSupplierName(supplier.getSupplierName());
        update.setWarehouseId(warehouseId);
        update.setWarehouseName(warehouse.getWarehouseName());
        update.setExpectedArrivalDate(dto.getExpectedArrivalDate());
        update.setTotalAmount(QtyUtil.toStored(totalAmount));
        update.setRemark(dto.getRemark());
        update.setVersion(dto.getVersion());
        int rows = purchaseOrderMapper.updateById(update);
        if (rows == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "数据已发生变化，请刷新后重试");
        }
        return getDetail(purchaseOrderId);
    }

    /**
     * 提交采购订单（DRAFT -> SUBMITTED）
     * @param purchaseOrderId 采购订单ID
     * @param version 乐观锁版本号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long purchaseOrderId, Integer version) {
        PurchaseOrder order = loadAndCheckStatus(purchaseOrderId, version, "仅草稿状态可提交", PurchaseOrderStatus.DRAFT);
        // 校验预计到货日期非空
        if (order.getExpectedArrivalDate() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "预计到货日期不能为空");
        }
        // 重新校验供应商、仓库、产品、供货关系
        revalidateOrderBusiness(order);
        LoginUser loginUser = UserContext.requireCurrentUser();
        // 更新状态为已提交
        PurchaseOrder update = new PurchaseOrder();
        update.setId(purchaseOrderId);
        update.setStatus(PurchaseOrderStatus.SUBMITTED.name());
        update.setSubmittedAt(LocalDateTime.now());
        update.setSubmittedById(loginUser.getUserId());
        update.setSubmittedByName(loginUser.getRealName());
        update.setVersion(version);
        int rows = purchaseOrderMapper.updateById(update);
        if (rows == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "数据已发生变化，请刷新后重试");
        }
    }

    /**
     * 审核采购订单（SUBMITTED -> APPROVED），并在同一事务内生成 PURCHASE_IN 待确认入库单
     * @param purchaseOrderId 采购订单ID
     * @param version 乐观锁版本号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long purchaseOrderId, Integer version) {
        PurchaseOrder order = loadAndCheckStatus(purchaseOrderId, version, "仅待审核状态可审核", PurchaseOrderStatus.SUBMITTED);
        // 校验预计到货日期非空
        if (order.getExpectedArrivalDate() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "预计到货日期不能为空");
        }
        // 重新校验供应商、仓库、产品、供货关系
        revalidateOrderBusiness(order);
        // 获取当前登录用户
        LoginUser loginUser = UserContext.requireCurrentUser();
        LocalDateTime now = LocalDateTime.now();
        // 更新采购订单状态为已审核
        PurchaseOrder update = new PurchaseOrder();
        update.setId(purchaseOrderId);
        update.setStatus(PurchaseOrderStatus.APPROVED.name());
        update.setApprovedById(loginUser.getUserId());
        update.setApprovedByName(loginUser.getRealName());
        update.setApprovedAt(now);

        update.setVersion(version);
        int rows = purchaseOrderMapper.updateById(update);
        if (rows == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "数据已发生变化，请刷新后重试");
        }
        // 生成 PURCHASE_IN 待确认入库单
        generatePurchaseInboundBill(order);
    }

    /**
     * 接收仓储模块的入库确认事实，回写采购订单累计入库数量和状态。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleInboundConfirmation(InboundBill bill, List<InboundBillItem> inboundItems) {
        if (!SourceType.PURCHASE_ORDER.name().equals(bill.getSourceType()) || bill.getSourceId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "入库单不是采购订单来源，不能回写采购订单");
        }
        PurchaseOrder order = purchaseOrderMapper.selectById(bill.getSourceId());
        if (order == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "采购订单不存在，无法完成入库确认");
        }
        if (!PurchaseOrderStatus.APPROVED.name().equals(order.getStatus())
                && !PurchaseOrderStatus.PARTIAL_INBOUND.name().equals(order.getStatus())) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "当前采购订单状态不允许入库确认");
        }
        // 校验入库单明细是否关联采购订单明细
        List<PurchaseOrderItem> purchaseItems = purchaseOrderItemService.list(
                new LambdaQueryWrapper<PurchaseOrderItem>()
                        .eq(PurchaseOrderItem::getPurchaseOrderId, order.getId()));
        // 创建采购订单明细id映射表
        Map<Long, PurchaseOrderItem> itemById = purchaseItems.stream()
                .collect(Collectors.toMap(PurchaseOrderItem::getId, item -> item));
        for (InboundBillItem inboundItem : inboundItems) {
            PurchaseOrderItem purchaseItem = itemById.get(inboundItem.getSourceItemId());
            if (purchaseItem == null) {
                log.error("采购订单[{}]入库回写异常:入库明细[{}]未关联当前采购订单明细(来源明细ID:{},产品编码:{},产品名称:{},入库单号:{})",
                        order.getPurchaseNo(), inboundItem.getId(), inboundItem.getSourceItemId(),
                        inboundItem.getProductCode(), inboundItem.getProductName(), inboundItem.getInboundNo());
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(),
                        "入库明细未关联当前采购订单明细(来源明细ID:" + inboundItem.getSourceItemId()
                                + ",产品编码:" + inboundItem.getProductCode()
                                + ",产品名称:" + inboundItem.getProductName() + ")");
            }
            // 校验入库确认数量是否超过采购订单剩余数量
            long currentQty = inboundItem.getCurrentQty() != null ? inboundItem.getCurrentQty() : 0L;
            long inboundQty = (purchaseItem.getInboundQty() != null ? purchaseItem.getInboundQty() : 0L) + currentQty;
            long planQty = purchaseItem.getQuantity() != null ? purchaseItem.getQuantity() : 0L;
            if (currentQty < 0) {
                log.error("采购订单[{}]入库回写异常:入库明细[{}]本次入库数量为负(来源明细ID:{},产品编码:{},产品名称:{},本次入库:{})",
                        order.getPurchaseNo(), inboundItem.getId(), inboundItem.getSourceItemId(),
                        inboundItem.getProductCode(), inboundItem.getProductName(),
                        QtyUtil.toDecimal(currentQty));
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(),
                        "入库确认数量不能为负(产品编码:" + inboundItem.getProductCode()
                                + ",产品名称:" + inboundItem.getProductName()
                                + ",本次入库:" + QtyUtil.toDecimal(currentQty) + ")");
            }
            if (inboundQty > planQty) {
                log.error("采购订单[{}]入库回写异常:入库明细[{}]入库数量超过剩余数量(来源明细ID:{},产品编码:{},产品名称:{},计划数量:{},已入库:{},本次入库:{})",
                        order.getPurchaseNo(), inboundItem.getId(), inboundItem.getSourceItemId(),
                        inboundItem.getProductCode(), inboundItem.getProductName(),
                        QtyUtil.toDecimal(planQty), QtyUtil.toDecimal(purchaseItem.getInboundQty()),
                        QtyUtil.toDecimal(currentQty));
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(),
                        "入库确认数量超过采购订单剩余数量(产品编码:" + inboundItem.getProductCode()
                                + ",产品名称:" + inboundItem.getProductName()
                                + ",计划数量:" + QtyUtil.toDecimal(planQty)
                                + ",已入库:" + QtyUtil.toDecimal(purchaseItem.getInboundQty())
                                + ",本次入库:" + QtyUtil.toDecimal(currentQty) + ")");
            }
            // 更新采购订单明细累计入库数量
            purchaseItem.setInboundQty(inboundQty);
        }
        if (!purchaseOrderItemService.updateBatchById(purchaseItems)) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "采购订单明细回写失败，请刷新后重试");
        }
        // 校验采购订单明细是否全部入库
        boolean allInbound = purchaseItems.stream().allMatch(item ->
                (item.getInboundQty() != null ? item.getInboundQty() : 0L) >= (item.getQuantity() != null ? item.getQuantity() : 0L));
        order.setStatus(allInbound ? PurchaseOrderStatus.INBOUND_DONE.name() : PurchaseOrderStatus.PARTIAL_INBOUND.name());
        if (purchaseOrderMapper.updateById(order) == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "采购订单已发生变化，请刷新后重试");
        }
        if (!allInbound) {
            generatePurchaseInboundBill(order);
        }
    }

    /**
     * 取消采购订单。已审核订单仅在尚未发生任何确认入库事实时允许取消。
     * @param purchaseOrderId 采购订单ID
     * @param version 乐观锁版本号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long purchaseOrderId, Integer version) {
        // 1. 加载采购订单
        PurchaseOrder existing = purchaseOrderMapper.selectById(purchaseOrderId);
        if (existing == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "采购订单不存在");
        }
        // 2. 校验采购订单状态是否可取消
        if (PurchaseOrderStatus.APPROVED.name().equals(existing.getStatus())) {
            sourceOperationLockSupport.acquire(SourceType.PURCHASE_ORDER.name(), existing.getId());
            existing = purchaseOrderMapper.selectById(purchaseOrderId);
            if (existing == null) {
                throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "采购订单已被其他人修改，请刷新后重试");
            }
        }
        // 3. 重复取消不重复变更采购订单状态
        if (PurchaseOrderStatus.CANCELLED.name().equals(existing.getStatus())) {
            return;
        }
        // 4. 校验采购订单状态是否可取消
        PurchaseOrder order = loadAndCheckStatus(purchaseOrderId, version,
                "仅草稿、待审核和未入库的已审核状态可取消",
                PurchaseOrderStatus.DRAFT, PurchaseOrderStatus.SUBMITTED, PurchaseOrderStatus.APPROVED);
        if (PurchaseOrderStatus.APPROVED.name().equals(order.getStatus())) {
            // 5. 已审核采购单取消时，必须先确认没有任何入库事实，再作废全部未确认入库工作单
            cancelPendingInboundBills(order);
        }
        // 更新状态为已取消
        PurchaseOrder update = new PurchaseOrder();
        update.setId(purchaseOrderId);
        update.setStatus(PurchaseOrderStatus.CANCELLED.name());
        update.setVersion(version);
        int rows = purchaseOrderMapper.updateById(update);
        if (rows == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "数据已发生变化，请刷新后重试");
        }
    }

    /**
     * 已审核采购单取消时，必须先确认没有任何入库事实，再作废全部未确认入库工作单。
     * 工作单使用自身乐观锁更新，和仓库确认并发时任一方失败后由外层事务统一回滚。
     */
    private void cancelPendingInboundBills(PurchaseOrder order) {
        // 校验采购订单是否有已确认入库事实
        List<InboundBill> allBills = inboundBillMapper.selectList(new LambdaQueryWrapper<InboundBill>()
                .eq(InboundBill::getSourceType, SourceType.PURCHASE_ORDER.name())
                .eq(InboundBill::getSourceId, order.getId()));
        boolean hasConfirmed = allBills.stream()
                .anyMatch(bill -> StockBillStatus.CONFIRMED.name().equals(bill.getStatus()));
        if (hasConfirmed) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "已发生入库事实的采购订单不允许取消");
        }
        // 作废全部未确认入库工作单
        for (InboundBill bill : allBills) {
            if (!StockBillStatus.DRAFT.name().equals(bill.getStatus())
                    && !StockBillStatus.PENDING_CONFIRM.name().equals(bill.getStatus())) {
                continue;
            }
            InboundBill update = new InboundBill();
            update.setId(bill.getId());
            update.setStatus(StockBillStatus.CANCELLED.name());
            update.setVersion(bill.getVersion());
            if (inboundBillMapper.updateById(update) == 0) {
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                        "入库单[" + bill.getInboundNo() + "]状态已变更，无法取消采购订单");
            }
        }
    }

    /**
     * 加载采购订单并校验状态与乐观锁版本
     */
    private PurchaseOrder loadAndCheckStatus(Long purchaseOrderId, Integer version,
                                             String statusMessage, PurchaseOrderStatus... allowedStatuses) {
        PurchaseOrder order = purchaseOrderMapper.selectById(purchaseOrderId);
        if (order == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
        // 校验状态是否在允许范围内
        boolean statusAllowed = false;
        for (PurchaseOrderStatus allowed : allowedStatuses) {
            if (allowed.name().equals(order.getStatus())) {
                statusAllowed = true;
                break;
            }
        }
        if (!statusAllowed) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), statusMessage);
        }
        validateVersion(order, version);
        return order;
    }

    /**
     * 校验乐观锁版本号是否匹配
     * @param order 采购订单实体
     * @param expectedVersion 期望的版本号
     */
    private void validateVersion(PurchaseOrder order, Integer expectedVersion) {
        if (order.getVersion() == null || !order.getVersion().equals(expectedVersion)) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "数据已发生变化，请刷新后重试");
        }
    }

    /**
     * 批量查询产品并返回产品ID到产品实体的映射
     * @param items 采购明细DTO列表
     * @return 产品ID到产品实体的映射
     */
    private Map<Long, Product> loadProducts(List<PurchaseOrderItemDto> items) {
        List<Long> productIds = items.stream()
                .map(item -> IdUtil.parseRequiredLongId(item.getProductId(), "产品ID"))
                .toList();
        return productMapper.selectByIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
    }

    /**
     * 校验供货关系：供货关系存在、属于当前供应商且与产品匹配
     * @param supplierId 供应商ID
     * @param supplierProductToProduct 供货关系ID到产品ID的映射
     */
    private void validateSupplierProductRelations(Long supplierId, Map<Long, Long> supplierProductToProduct) {
        // 校验供货关系存在
        List<SupplierProduct> supplierProducts = supplierProductMapper.selectByIds(supplierProductToProduct.keySet());
        Map<Long, SupplierProduct> spMap = supplierProducts.stream()
                .collect(Collectors.toMap(SupplierProduct::getId, sp -> sp));
        for (Map.Entry<Long, Long> entry : supplierProductToProduct.entrySet()) {
            Long spId = entry.getKey();
            Long productId = entry.getValue();
            SupplierProduct sp = spMap.get(spId);
            if (sp == null) {
                throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "供货关系不存在: " + spId);
            }
            if (!sp.getSupplierId().equals(supplierId)) {
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "供货关系不属于当前供应商: " + spId);
            }
            if (!sp.getProductId().equals(productId)) {
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "供货关系与产品不匹配: " + spId);
            }
        }
    }

    /**
     * 重新校验采购订单的业务数据：供应商、仓库、明细、供货关系
     * @param order 采购订单实体
     */
    private void revalidateOrderBusiness(PurchaseOrder order) {
        // 校验供应商存在
        if (supplierMapper.selectById(order.getSupplierId()) == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "供应商不存在");
        }
        // 校验仓库存在
        if (warehouseMapper.selectById(order.getWarehouseId()) == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "仓库不存在");
        }
        // 查询明细
        List<PurchaseOrderItem> items = purchaseOrderItemService.list(
                new LambdaQueryWrapper<PurchaseOrderItem>()
                        .eq(PurchaseOrderItem::getPurchaseOrderId, order.getId()));
        if (items.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "采购明细不能为空");
        }
        for (PurchaseOrderItem item : items) {
            if (item.getQuantityPrecision() == null || item.getQuantityPrecision() < 0 || item.getQuantityPrecision() > 2) {
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "采购明细数量精度快照无效: " + item.getId());
            }
            BigDecimal quantity = QtyUtil.toDecimal(item.getQuantity());
            if (quantity == null || quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > item.getQuantityPrecision()) {
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "采购明细数量不符合精度快照: " + item.getId());
            }
        }
        // 校验供货关系仍然有效
        Map<Long, Long> supplierProductToProduct = items
                .stream()
                .collect(Collectors.toMap(PurchaseOrderItem::getSupplierProductId, PurchaseOrderItem::getProductId));
        if(supplierProductToProduct.size() != items.size()) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "采购明细有误,有产品无与之匹配的供应商");
        }
        validateSupplierProductRelations(order.getSupplierId(), supplierProductToProduct);
    }

    /**
     * 客户端需要回传精度以保证表单契约完整，但精度只能由服务端产品主数据裁决。
     */
    private void validateQuantityPrecision(PurchaseOrderItemDto itemDto, Integer serverPrecision) {
        int precision = serverPrecision == null ? 0 : serverPrecision;
        if (!Integer.valueOf(precision).equals(itemDto.getQuantityPrecision())) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "数量精度与当前产品不一致，请刷新产品后重试");
        }
        BigDecimal quantity = itemDto.getQuantity();
        if (quantity.stripTrailingZeros().scale() > precision) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "采购数量最多保留 " + precision + " 位小数");
        }
    }

    /**
     * 编辑时，已有明细必须使用下单时的精度快照；仅新增明细才读取当前产品精度。
     */
    private Integer resolveUpdateQuantityPrecision(PurchaseOrderItemDto itemDto, Long productId, Integer productPrecision,
                                                    Map<Long, PurchaseOrderItem> existingItemById) {
        if (StrUtil.isBlank(itemDto.getPurchaseOrderItemId())) {
            validateQuantityPrecision(itemDto, productPrecision);
            return productPrecision == null ? 0 : productPrecision;
        }
        Long itemId = IdUtil.parseRequiredLongId(itemDto.getPurchaseOrderItemId(), "采购订单明细ID");
        PurchaseOrderItem existingItem = existingItemById.get(itemId);
        if (existingItem == null) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "采购订单明细不属于当前订单: " + itemDto.getPurchaseOrderItemId());
        }
        if (!productId.equals(existingItem.getProductId())) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "变更采购明细产品时必须作为新增明细提交");
        }
        int snapshotPrecision = existingItem.getQuantityPrecision() == null ? 0 : existingItem.getQuantityPrecision();
        if (!Integer.valueOf(snapshotPrecision).equals(itemDto.getQuantityPrecision())) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "数量精度与原采购明细快照不一致，请刷新后重试");
        }
        if (itemDto.getQuantity().stripTrailingZeros().scale() > snapshotPrecision) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "采购数量最多保留 " + snapshotPrecision + " 位小数");
        }
        return snapshotPrecision;
    }

    /**
     * 审核通过后生成 PURCHASE_IN 待确认入库单
     * @param order 采购订单实体
     */
    private void generatePurchaseInboundBill(PurchaseOrder order) {
        Long activeCount = inboundBillMapper.selectCount(new LambdaQueryWrapper<InboundBill>()
                .eq(InboundBill::getSourceType, SourceType.PURCHASE_ORDER.name())
                .eq(InboundBill::getSourceId, order.getId())
                .in(InboundBill::getStatus, StockBillStatus.DRAFT.name(), StockBillStatus.PENDING_CONFIRM.name()));
        if (activeCount != null && activeCount > 0) {
            log.warn("采购订单[{}]已存在未确认入库单，跳过生成", order.getPurchaseNo());
            return;
        }
        // 查询采购明细
        List<PurchaseOrderItem> items = purchaseOrderItemService.list(
                new LambdaQueryWrapper<PurchaseOrderItem>()
                        .eq(PurchaseOrderItem::getPurchaseOrderId, order.getId()));
        if (items.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "采购明细为空，无法生成入库单");
        }
        // 已全部入库时不创建空的待确认入库单；正常分支至少保留一条剩余明细。
        boolean hasRemainingQuantity = items.stream().anyMatch(item -> {
            long planQty = item.getQuantity() != null ? item.getQuantity() : 0L;
            long inboundQty = item.getInboundQty() != null ? item.getInboundQty() : 0L;
            return planQty > inboundQty;
        });
        if (!hasRemainingQuantity) {
            return;
        }
        // 生成入库单号
        String inboundNo = billNoGenerator.nextNo(InboundType.PURCHASE_IN.billNoPrefix(),
                this::findMaxInboundBillSequence);
        // 获取当前登录用户（审核人即入库单负责人）
        LoginUser loginUser = UserContext.requireCurrentUser();
        Long currentUserId = loginUser.getUserId();
        String currentUserName = loginUser.getRealName();
        // 构建入库单主表
        InboundBill bill = new InboundBill()
                .setInboundNo(inboundNo)
                .setInboundType(InboundType.PURCHASE_IN.name())
                .setSourceType(SourceType.PURCHASE_ORDER.name())
                .setSourceId(order.getId())
                .setSourceNo(order.getPurchaseNo())
                .setSourcePartyId(order.getSupplierId())
                .setSourcePartyName(order.getSupplierName())
                .setEntryMode(EntryMode.SOURCE_GENERATED.name())
                .setWarehouseId(order.getWarehouseId())
                .setWarehouseName(order.getWarehouseName())
                .setStatus(StockBillStatus.PENDING_CONFIRM.name())
                .setCreatedById(currentUserId)
                .setCreatedByName(currentUserName)
                .setResponsibleById(currentUserId)
                .setResponsibleByName(currentUserName)
                .setRemark(order.getRemark());
        inboundBillMapper.insert(bill);
        // 构建入库单明细
        List<InboundBillItem> billItems = new ArrayList<>();
        for (PurchaseOrderItem item : items) {
            Integer precision = item.getQuantityPrecision() != null ? item.getQuantityPrecision() : 0;
            Long planQty = item.getQuantity() != null ? item.getQuantity() : 0L;
            Long processedQty = item.getInboundQty() != null ? item.getInboundQty() : 0L;
            long pendingQty = Math.max(0L, planQty - processedQty);
            if (pendingQty == 0L) {
                continue;
            }
            InboundBillItem billItem = new InboundBillItem()
                    .setInboundBillId(bill.getId())
                    .setInboundNo(inboundNo)
                    .setSourceItemId(item.getId())
                    .setProductId(item.getProductId())
                    .setProductCode(item.getProductCode())
                    .setProductName(item.getProductName())
                    .setUnitName(item.getUnitName())
                    .setQuantityPrecision(precision)
                    .setPlanQty(planQty)
                    .setProcessedQty(processedQty)
                    .setCurrentQty(0L)
                    .setPendingQty(pendingQty)
                    .setQualifiedQty(0L)
                    .setDefectiveQty(0L);
            billItems.add(billItem);
        }
        // 等效于 IService#saveBatch：单条循环入库（MyBatis-Plus saveBatch 默认实现也是循环 insert）
        for (InboundBillItem billItem : billItems) {
            inboundBillItemMapper.insert(billItem);
        }
    }

    /**
     * 构建采购订单完成摘要
     */
    private PurchaseOrderFulfillmentSummaryVo buildFulfillmentSummary(PurchaseOrder order,
                                                                        List<PurchaseOrderItemVo> items) {
        BigDecimal totalAmount = QtyUtil.defaultZero(QtyUtil.toDecimal(order.getTotalAmount()));
        BigDecimal inboundAmount = items.stream()
                .map(item -> {
                    BigDecimal inboundQty = item.getInboundQty() == null ? BigDecimal.ZERO : item.getInboundQty();
                    BigDecimal quantity = item.getQuantity() == null ? BigDecimal.ZERO : item.getQuantity();
                BigDecimal unitPrice = QtyUtil.defaultZero(QtyUtil.toDecimal(item.getUnitPrice()));
                    return inboundQty.min(quantity).multiply(unitPrice);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal completionRate = totalAmount.signum() == 0 ? BigDecimal.ZERO
                : inboundAmount.multiply(BigDecimal.valueOf(100)).divide(totalAmount, 2, RoundingMode.HALF_UP);
        return new PurchaseOrderFulfillmentSummaryVo()
                .setCalculationMode("AMOUNT_WEIGHTED")
                .setTotalAmount(totalAmount)
                .setInboundAmount(inboundAmount)
                .setCompletionRate(completionRate);
    }
    /**
     * 构建采购订单时间线
     */
    private List<PurchaseOrderTimelineItemVo> buildTimeline(PurchaseOrder order) {
        List<PurchaseOrderTimelineItemVo> timeline = new ArrayList<>();
        addTimelineItem(timeline, "CREATED", order.getCreatedByName(), order.getCreateTime(), null, null);
        addTimelineItem(timeline, "SUBMITTED", order.getSubmittedByName(), order.getSubmittedAt(), null, null);
        addTimelineItem(timeline, "APPROVED", order.getApprovedByName(), order.getApprovedAt(), null, null);
        List<InboundBill> inboundBills = inboundBillMapper.selectList(new LambdaQueryWrapper<InboundBill>()
                .eq(InboundBill::getSourceType, SourceType.PURCHASE_ORDER.name())
                .eq(InboundBill::getSourceId, order.getId())
                .orderByAsc(InboundBill::getCreateTime));
        for (InboundBill bill : inboundBills) {
            addTimelineItem(timeline, "INBOUND_CREATED", "系统", bill.getCreateTime(), bill.getId(), bill.getInboundNo());
            if (StockBillStatus.CONFIRMED.name().equals(bill.getStatus())) {
                addTimelineItem(timeline, "INBOUND_CONFIRMED", bill.getConfirmedByName(), bill.getConfirmedAt(),
                        bill.getId(), bill.getInboundNo());
            }
        }
        timeline.sort(Comparator.comparing(PurchaseOrderTimelineItemVo::getOccurredAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return timeline;
    }

    /**
     * 添加采购订单时间线事件
     */
    private void addTimelineItem(List<PurchaseOrderTimelineItemVo> timeline, String event, String operatorName,
                                 LocalDateTime occurredAt, Long inboundBillId, String inboundBillNo) {
        if (occurredAt == null) {
            return;
        }
        timeline.add(new PurchaseOrderTimelineItemVo()
                .setEvent(event)
                .setOperatorName(StrUtil.blankToDefault(operatorName, "系统"))
                .setOccurredAt(occurredAt)
                .setInboundBillId(inboundBillId)
                .setInboundBillNo(inboundBillNo));
    }

    /**
     * 查询入库单表当天最大单号序号，供 Redis 序列首次初始化使用
     * @return 当天最大序号，无记录返回0
     */
    private long findMaxInboundBillSequence() {
        List<Object> billNos = inboundBillMapper.selectObjs(
                new LambdaQueryWrapper<InboundBill>().select(InboundBill::getInboundNo));
        return billNoGenerator.findMaxExistingSequence(InboundType.PURCHASE_IN.billNoPrefix(), billNos);
    }

    /**
     * 明细中100倍存储字段转业务值
     */
    private void convertItemStoredValues(PurchaseOrderItemVo vo) {
        vo.setQuantity(QtyUtil.toDecimal(vo.getQuantity()));
        vo.setInboundQty(QtyUtil.toDecimal(vo.getInboundQty()));
        vo.setUnitPrice(QtyUtil.toDecimal(vo.getUnitPrice()));
        vo.setTotalAmount(QtyUtil.toDecimal(vo.getTotalAmount()));
        vo.setSelectedSupplierScore(QtyUtil.toDecimal(vo.getSelectedSupplierScore()));
    }

}
