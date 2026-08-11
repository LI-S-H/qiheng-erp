package com.qiheng.erp.sales.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.util.BillNoGenerator;
import com.qiheng.erp.common.util.IdUtil;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.product.mapper.ProductMapper;
import com.qiheng.erp.sales.domain.customer.entity.Customer;
import com.qiheng.erp.sales.domain.salesorder.dto.SalesOrderCreateDto;
import com.qiheng.erp.sales.domain.salesorder.dto.SalesOrderDraftItemDto;
import com.qiheng.erp.sales.domain.salesorder.dto.SalesOrderPageDto;
import com.qiheng.erp.sales.domain.salesorder.dto.SalesOrderUpdateDto;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrderItem;
import com.qiheng.erp.sales.domain.salesorder.enums.SalesOrderStatus;
import com.qiheng.erp.sales.domain.salesorder.vo.SalesOrderDetailVo;
import com.qiheng.erp.sales.domain.salesorder.vo.SalesOrderItemVo;
import com.qiheng.erp.sales.domain.salesorder.vo.SalesOrderVo;
import com.qiheng.erp.sales.mapper.CustomerMapper;
import com.qiheng.erp.sales.mapper.SalesOrderItemMapper;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import com.qiheng.erp.sales.service.ISalesOrderItemService;
import com.qiheng.erp.sales.service.ISalesOrderService;
import com.qiheng.erp.warehouse.domain.outbound.entity.OutboundBill;
import com.qiheng.erp.warehouse.domain.outbound.entity.OutboundBillItem;
import com.qiheng.erp.warehouse.domain.outbound.enums.OutboundType;
import com.qiheng.erp.warehouse.domain.common.enums.EntryMode;
import com.qiheng.erp.warehouse.domain.common.enums.SourceType;
import com.qiheng.erp.warehouse.domain.stockbill.enums.StockBillStatus;
import com.qiheng.erp.warehouse.domain.warehouse.entity.Warehouse;
import com.qiheng.erp.warehouse.domain.warehousestock.entity.WarehouseStock;
import com.qiheng.erp.warehouse.mapper.OutboundBillMapper;
import com.qiheng.erp.warehouse.mapper.WarehouseMapper;
import com.qiheng.erp.warehouse.service.IOutboundBillItemService;
import com.qiheng.erp.warehouse.service.IWarehouseStockService;
import com.qiheng.erp.warehouse.service.support.SourceOperationLockSupport;
import com.qiheng.erp.warehouse.service.support.WarehouseStockLockSupport;
import com.qiheng.erp.warehouse.service.support.WarehouseStockReservationSupport;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 销售订单主表 服务实现类
 * </p>
 *
 * @author Li
 * @since 2026-08-10
 */
@Service
@Slf4j
public class SalesOrderServiceImpl extends ServiceImpl<SalesOrderMapper, SalesOrder> implements ISalesOrderService {

    /**
     * 客户 + 仓库 校验结果载体（validateCustomerAndWarehouse 返回值）
     */
    private record CustomerWarehouse(Customer customer, Warehouse warehouse) {}

    @Autowired
    private SalesOrderMapper salesOrderMapper;
    @Autowired
    private SalesOrderItemMapper salesOrderItemMapper;
    @Autowired
    private ISalesOrderItemService salesOrderItemService;
    @Autowired
    private CustomerMapper customerMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private WarehouseMapper warehouseMapper;
    @Autowired
    private BillNoGenerator billNoGenerator;
    @Autowired
    private WarehouseStockLockSupport warehouseStockLockSupport;
    @Autowired
    private IWarehouseStockService warehouseStockService;
    @Autowired
    private SourceOperationLockSupport sourceOperationLockSupport;
    @Autowired
    private OutboundBillMapper outboundBillMapper;
    @Autowired
    private com.qiheng.erp.warehouse.service.IOutboundBillService outboundBillService;
    @Autowired
    private IOutboundBillItemService outboundBillItemService;
    @Autowired
    private WarehouseStockReservationSupport warehouseStockReservationSupport;

    /**
     * 销售订单分页查询（逻辑删除过滤按全局配置自动追加）
     * @param dto 分页查询参数
     * @return 分页结果集
     */
    @Override
    public PageResult<SalesOrderVo> page(SalesOrderPageDto dto) {
        Long customerId = IdUtil.parseOptionalLongId(dto.getCustomerId(), "客户ID");
        Long warehouseId = IdUtil.parseOptionalLongId(dto.getWarehouseId(), "出库仓库ID");
        LambdaQueryWrapper<SalesOrder> wrapper = new LambdaQueryWrapper<SalesOrder>()
                .like(StrUtil.isNotBlank(dto.getSalesNo()), SalesOrder::getSalesNo, dto.getSalesNo())
                .eq(customerId != null, SalesOrder::getCustomerId, customerId)
                .eq(warehouseId != null, SalesOrder::getWarehouseId, warehouseId)
                .eq(StrUtil.isNotBlank(dto.getStatus()), SalesOrder::getStatus, dto.getStatus())
                .orderByDesc(SalesOrder::getCreateTime);
        Page<SalesOrder> result = salesOrderMapper.selectPage(dto.toPage(), wrapper);
        return PageResult.of(
                result.getRecords().stream().map(this::toVo).toList(),
                (int) result.getTotal(),
                (int) result.getCurrent(),
                (int) result.getSize()
        );
    }

    /**
     * 获取销售订单详情（主表 + 明细数组）
     * @param salesOrderId 销售订单ID
     * @return 销售订单详情VO
     */
    @Override
    public SalesOrderDetailVo getDetail(Long salesOrderId) {
        SalesOrder order = salesOrderMapper.selectById(salesOrderId);
        if (order == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
        List<SalesOrderItem> items = salesOrderItemMapper.selectList(
                new LambdaQueryWrapper<SalesOrderItem>()
                        .eq(SalesOrderItem::getSalesOrderId, salesOrderId)
        );
        SalesOrderDetailVo detail = new SalesOrderDetailVo();
        BeanUtil.copyProperties(toVo(order), detail);
        detail.setItems(items.stream().map(this::toItemVo).toList());
        return detail;
    }

    /**
     * 新增销售订单草稿（后端生成销售单号、写入客户/仓库/产品快照、数量×100 持久化、重算订单总金额）
     * @param dto 草稿新增请求 DTO
     * @return 新增后的销售订单详情VO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SalesOrderDetailVo createDraft(SalesOrderCreateDto dto) {
        Long customerId = IdUtil.parseRequiredLongId(dto.getCustomerId(), "客户ID");
        Long warehouseId = IdUtil.parseRequiredLongId(dto.getWarehouseId(), "出库仓库ID");
        // 1. 校验客户/仓库存在 + 启用（共用私有方法，返回实体供后续使用）
        CustomerWarehouse cw = validateCustomerAndWarehouse(customerId, warehouseId);
        // 3. 批量查询产品（避免循环查库）
        List<Long> productIds = dto.getItems().stream()
                .map(item -> IdUtil.parseRequiredLongId(item.getProductId(), "产品ID"))
                .toList();
        Map<Long, Product> productMap = productMapper.selectByIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
        // 当前登录用户
        LoginUser loginUser = UserContext.requireCurrentUser();
        // 4. 生成销售单号（Redis 按月重置，单号按天显示 yyyyMMdd）
        String salesNo = billNoGenerator.nextNo("SO");
        // 4. 校验明细 + 累加总金额（重复产品 / 产品启用 / 数量精度）
        BigDecimal totalAmount = validateItemsAndComputeTotal(dto.getItems(), productMap);
        // 5. 构建明细实体（lockedQty = 0，salesOrderId 由插入主表后回填）
        List<SalesOrderItem> items = new ArrayList<>();
        for (SalesOrderDraftItemDto itemDto : dto.getItems()) {
            Product product = productMap.get(IdUtil.parseRequiredLongId(itemDto.getProductId(), "产品ID"));
            items.add(buildSalesOrderItem(itemDto, product, null, salesNo, 0L));
        }
        // 6. 插入主表
        SalesOrder order = new SalesOrder();
        order.setSalesNo(salesNo);
        order.setCustomerId(customerId);
        order.setCustomerCode(cw.customer.getCustomerCode());
        order.setCustomerName(cw.customer.getCustomerName());
        order.setWarehouseId(warehouseId);
        order.setWarehouseName(cw.warehouse.getWarehouseName());
        order.setStatus(SalesOrderStatus.DRAFT.name());
        order.setExpectedDeliveryDate(dto.getExpectedDeliveryDate());
        order.setTotalAmount(QtyUtil.toStoredInt(totalAmount));
        order.setCreatedById(loginUser.getUserId());
        order.setCreatedByName(loginUser.getRealName());
        order.setRemark(dto.getRemark());
        salesOrderMapper.insert(order);
        // 7. 回填明细的订单ID并批量插入
        items.forEach(item -> item.setSalesOrderId(order.getId()));
        salesOrderItemService.saveBatch(items);
        return getDetail(order.getId());
    }

    /**
     * 提交销售订单（DRAFT → SUBMITTED，数据库行锁锁定可用库存，同步更新明细与主表状态）
     * @param salesOrderId 销售订单ID
     * @param version 乐观锁版本号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long salesOrderId, Integer version) {
        // 1. 校验订单存在 + 状态为 DRAFT + 乐观锁版本
        SalesOrder order = salesOrderMapper.selectById(salesOrderId);
        if (order == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
        if (!SalesOrderStatus.DRAFT.name().equals(order.getStatus())) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(),
                    "仅草稿状态可提交，当前状态: " + order.getStatus());
        }
        validateVersion(order, version);
        // 2. 校验预计发货日期非空且不早于今天
        if (order.getExpectedDeliveryDate() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "预计发货日期不能为空");
        }
        if (order.getExpectedDeliveryDate().isBefore(LocalDate.now())) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "预计发货日期不能早于今天");
        }
        // 3. 查明细 + 重新校验客户/仓库/产品当前启用状态（草稿后状态可能变化）
        List<SalesOrderItem> items = salesOrderItemMapper.selectList(
                new LambdaQueryWrapper<SalesOrderItem>()
                        .eq(SalesOrderItem::getSalesOrderId, salesOrderId)
        );
        if (items.isEmpty()) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "销售订单无明细，无法提交");
        }
        for (SalesOrderItem item : items) {
            if (item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(),
                        "产品 " + item.getProductCode() + " 销售数量必须大于0");
            }
        }
        validateOrderBusiness(order, items);
        // 4. 提取待锁定产品 ID（去重排序，配合 WarehouseStockLockSupport 避免多产品死锁）
        List<Long> productIds = items.stream()
                .map(SalesOrderItem::getProductId)
                .distinct()
                .sorted()
                .toList();
        // 5. 数据库行锁：SELECT ... FOR UPDATE 同仓库的所有相关产品库存
        Map<Long, WarehouseStock> lockedStocks =
                warehouseStockLockSupport.lockExistingStocks(order.getWarehouseId(), productIds);
        // 5. 校验每条明细可用库存：available = stock_qty - locked_qty >= quantity
        // （建单时已校验同一产品不重复，故每条明细对应唯一产品，无需汇总）
        for (SalesOrderItem item : items) {
            WarehouseStock stock = lockedStocks.get(item.getProductId());
            if (stock == null) {
                throw new BizException(ErrorCode.STOCK_INSUFFICIENT.getCode(),
                        "产品 " + item.getProductCode() + " 在该仓库无库存记录");
            }
            long stockQty = stock.getStockQty() == null ? 0L : stock.getStockQty();
            long lockedQty = stock.getLockedQty() == null ? 0L : stock.getLockedQty();
            long available = stockQty - lockedQty;
            long need = item.getQuantity();
            if (available < need) {
                throw new BizException(ErrorCode.STOCK_INSUFFICIENT.getCode(),
                        "产品 " + item.getProductCode() + " 可用库存不足，可用: "
                                + QtyUtil.toDecimal(available) + "，需锁定: " + QtyUtil.toDecimal(need));
            }
            // 累加库存锁定量（同一事务内，写后读其他事务看不到未提交的数据，行锁保证并发安全）
            stock.setLockedQty(lockedQty + need);
            // 设置明细的已锁定数量（DRAFT 状态下 lockedQty 必须为 0，否则数据异常）
            long itemLocked = item.getLockedQty() == null ? 0L : item.getLockedQty();
            if (itemLocked != 0L) {
                throw new BizException(ErrorCode.STATUS_INVALID.getCode(),
                        "产品 " + item.getProductCode() + " 明细锁定数量异常，请联系管理员");
            }
            item.setLockedQty(need);
        }
        // 6. 批量更新库存余额（@Version 乐观锁，任一失败即抛错，整个事务回滚）
        if (!warehouseStockService.updateBatchById(List.copyOf(lockedStocks.values()))) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "库存数据已被其他人修改，请刷新后重试");
        }
        // 7. 更新主表：状态 SUBMITTED + 提交人 + 提交时间 + 锁定时间（乐观锁校验）
        LoginUser loginUser = UserContext.requireCurrentUser();
        LocalDateTime now = LocalDateTime.now();
        SalesOrder update = new SalesOrder();
        update.setId(salesOrderId);
        update.setStatus(SalesOrderStatus.SUBMITTED.name());
        update.setSubmittedAt(now);
        update.setSubmittedById(loginUser.getUserId());
        update.setSubmittedByName(loginUser.getRealName());
        update.setLockedAt(now);
        update.setVersion(version);
        int rows = salesOrderMapper.updateById(update);
        if (rows == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                    "数据已发生变化，请刷新后重试");
        }
        // 8. 批量更新明细的已锁定数量（主表已更新为 SUBMITTED，并发编辑已被状态校验挡住）
        salesOrderItemService.updateBatchById(items);
    }

    /**
     * 审核销售订单（SUBMITTED → APPROVED，同一事务内生成 SALES_OUT 待确认出库单）
     * @param salesOrderId 销售订单ID
     * @param version 乐观锁版本号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long salesOrderId, Integer version) {
        // 1. 校验订单存在 + 状态为 SUBMITTED + 乐观锁版本号
        SalesOrder order = salesOrderMapper.selectById(salesOrderId);
        if (order == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
        if (!SalesOrderStatus.SUBMITTED.name().equals(order.getStatus())) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(),
                    "仅待审核状态可审核，当前状态: " + order.getStatus());
        }
        validateVersion(order, version);
        // 2. 校验预计发货日期非空且不早于今天
        if (order.getExpectedDeliveryDate() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "预计发货日期不能为空");
        }
        if (order.getExpectedDeliveryDate().isBefore(LocalDate.now())) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "预计发货日期不能早于今天");
        }
        // 3. 查明细 + 校验每条明细库存锁定结果（理论上 submit 已锁过，兜底检测数据错乱）
        List<SalesOrderItem> items = salesOrderItemMapper.selectList(
                new LambdaQueryWrapper<SalesOrderItem>()
                        .eq(SalesOrderItem::getSalesOrderId, salesOrderId)
        );
        if (items.isEmpty()) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "销售订单无明细，无法审核");
        }
        validateOrderBusiness(order, items);
        for (SalesOrderItem item : items) {
            if (item.getQuantity() == null || item.getLockedQty() == null
                    || !item.getLockedQty().equals(item.getQuantity())) {
                log.error("销售订单[{}]审核异常:明细[{}] 锁定数量={} 销售数量={}",
                        order.getSalesNo(), item.getProductCode(),
                        item.getLockedQty(), item.getQuantity());
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                        "数据异常:明细锁定数量与销售数量不一致,请联系运维");
            }
        }
        // 4. 更新主表：状态 APPROVED + 审核人/时间（乐观锁校验，先拿审核权再生成出库单）
        LoginUser loginUser = UserContext.requireCurrentUser();
        LocalDateTime now = LocalDateTime.now();
        SalesOrder update = new SalesOrder();
        update.setId(salesOrderId);
        update.setStatus(SalesOrderStatus.APPROVED.name());
        update.setApprovedById(loginUser.getUserId());
        update.setApprovedByName(loginUser.getRealName());
        update.setApprovedAt(now);
        update.setVersion(version);
        int rows = salesOrderMapper.updateById(update);
        if (rows == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                    "数据已发生变化，请刷新后重试");
        }
        // 5. 生成 SALES_OUT 待确认出库单（主表已 APPROVED，审核权已拿到）
        log.info("审核销售订单[{}]，生成待确认出库单", order.getSalesNo());
        generateSalesOutboundBill(order, items);
    }

    /**
     * 取消销售订单（DRAFT / SUBMITTED → CANCELLED；SUBMITTED 需同一事务内释放锁定库存）
     * @param salesOrderId 销售订单ID
     * @param version 乐观锁版本号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long salesOrderId, Integer version) {
        // 1. 校验订单存在
        SalesOrder order = salesOrderMapper.selectById(salesOrderId);
        if (order == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
        // 2. 幂等：已取消直接返回（与采购保持一致）
        if (SalesOrderStatus.CANCELLED.name().equals(order.getStatus())) {
            return;
        }
        // 3. DRAFT / SUBMITTED / APPROVED 可取消；已出库状态不得取消
        String currentStatus = order.getStatus();
        boolean cancellable = SalesOrderStatus.DRAFT.name().equals(currentStatus)
                || SalesOrderStatus.SUBMITTED.name().equals(currentStatus)
                || SalesOrderStatus.APPROVED.name().equals(currentStatus);
        if (!cancellable) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(),
                    "已出库的销售订单不得取消，当前状态: " + currentStatus);
        }
        // 4. 乐观锁版本号预校验
        validateVersion(order, version);
        // 5. 查明细
        List<SalesOrderItem> items = salesOrderItemMapper.selectList(
                new LambdaQueryWrapper<SalesOrderItem>()
                        .eq(SalesOrderItem::getSalesOrderId, salesOrderId)
        );
        // 6. APPROVED 分支：先加分布式锁防止与仓库 confirmBill SALES_OUT 跨资源并发死锁，
        // 再校验出库单未确认 + 取消关联出库单
        boolean needReleaseStock = false;
        if (SalesOrderStatus.APPROVED.name().equals(currentStatus)) {
            sourceOperationLockSupport.acquire(SourceType.SALES_ORDER.name(), salesOrderId);
            // 6.1 查询关联出库单
            List<OutboundBill> outboundBills = outboundBillMapper.selectList(
                    new LambdaQueryWrapper<OutboundBill>()
                            .eq(OutboundBill::getSourceType, SourceType.SALES_ORDER.name())
                            .eq(OutboundBill::getSourceId, salesOrderId)
                            .notIn(OutboundBill::getStatus, StockBillStatus.CANCELLED.name())
            );
            // 6.2 一次遍历：校验已确认出库 + 收集待取消的出库单
            List<OutboundBill> billsToCancel = new ArrayList<>();
            for (OutboundBill ob : outboundBills) {
                if (StockBillStatus.CONFIRMED.name().equals(ob.getStatus())) {
                    throw new BizException(ErrorCode.STATUS_INVALID.getCode(),
                            "出库单已确认出库，无法取消销售订单，出库单号: " + ob.getOutboundNo());
                }
                ob.setStatus(StockBillStatus.CANCELLED.name());
                billsToCancel.add(ob);
            }
            // 6.3 批量取消出库单（明细无状态字段，随主表取消即可；@Version 乐观锁任一失败即抛错）
            if (!billsToCancel.isEmpty()
                    && !outboundBillService.updateBatchById(billsToCancel)) {
                throw new BizException(ErrorCode.STATUS_INVALID.getCode(),
                        "出库单状态已被其他人修改，请刷新后重试");
            }
            needReleaseStock = true;
        } else if (SalesOrderStatus.SUBMITTED.name().equals(currentStatus)) {
            needReleaseStock = true;
        }
        // 7. 校验明细数据完整性 + 准备库存释放（SUBMITTED / APPROVED 都需要释放库存）
        Map<Long, WarehouseStock> lockedStocks = null;
        if (needReleaseStock) {
            // 7.1 兜底：每条明细 lockedQty 必须等于 quantity（理论上 submit 已对齐）
            for (SalesOrderItem item : items) {
                if (item.getQuantity() == null || item.getLockedQty() == null
                        || !item.getLockedQty().equals(item.getQuantity())) {
                    log.error("销售订单[{}]取消异常:明细[{}] 锁定数量={} 销售数量={}",
                            order.getSalesNo(), item.getProductCode(),
                            item.getLockedQty(), item.getQuantity());
                    throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                            "数据异常:明细锁定数量与销售数量不一致,请联系运维");
                }
            }
            // 7.2 提取 productIds 去重排序，配合 WarehouseStockLockSupport 避免多产品死锁
            List<Long> productIds = items.stream()
                    .map(SalesOrderItem::getProductId)
                    .distinct()
                    .sorted()
                    .toList();
            // 7.3 数据库行锁：SELECT ... FOR UPDATE
            lockedStocks = warehouseStockLockSupport.lockExistingStocks(order.getWarehouseId(), productIds);
            // 7.4 校验库存记录存在 + 锁定量足够释放（仅修改内存，不写库）
            for (SalesOrderItem item : items) {
                WarehouseStock stock = lockedStocks.get(item.getProductId());
                if (stock == null) {
                    log.error("销售订单[{}]取消异常:产品[{}] 在该仓库无库存记录",
                            order.getSalesNo(), item.getProductCode());
                    throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                            "数据异常:库存记录缺失,请联系运维");
                }
                long curLocked = stock.getLockedQty() == null ? 0L : stock.getLockedQty();
                long toRelease = item.getLockedQty();
                if (curLocked < toRelease) {
                    log.error("销售订单[{}]取消异常:产品[{}] 库存锁定量={} 小于待释放={}",
                            order.getSalesNo(), item.getProductCode(), curLocked, toRelease);
                    throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                            "数据异常:库存锁定量小于待释放量,请联系运维");
                }
                stock.setLockedQty(curLocked - toRelease);
            }
        } else {
            // 7.5 DRAFT 分支：DRAFT 状态明细 lockedQty 必须为 0，兜底检测
            for (SalesOrderItem item : items) {
                if (item.getLockedQty() != null && item.getLockedQty() != 0L) {
                    log.error("销售订单[{}]取消异常:DRAFT 明细[{}] 锁定数量异常值={}",
                            order.getSalesNo(), item.getProductCode(), item.getLockedQty());
                    throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                            "数据异常:DRAFT 状态明细存在锁定数量,请联系运维");
                }
            }
        }
        // 8. 更新主表：状态 CANCELLED（乐观锁校验，先拿取消权再释放库存）
        SalesOrder update = new SalesOrder();
        update.setId(salesOrderId);
        update.setStatus(SalesOrderStatus.CANCELLED.name());
        update.setVersion(version);
        int rows = salesOrderMapper.updateById(update);
        if (rows == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                    "数据已发生变化，请刷新后重试");
        }
        // 9. 释放锁定库存（SUBMITTED / APPROVED 分支，主表已 CANCELLED，取消权已拿到）
        if (needReleaseStock) {
            // 9.1 批量更新库存余额（@Version 乐观锁，任一失败即抛错，整个事务回滚）
            if (!warehouseStockService.updateBatchById(List.copyOf(lockedStocks.values()))) {
                throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "库存数据已被其他人修改，请刷新后重试");
            }
            // 9.2 重置明细的已锁定数量
            for (SalesOrderItem item : items) {
                item.setLockedQty(0L);
            }
            if (!salesOrderItemService.updateBatchById(items)) {
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                        "销售明细数据已被其他人修改，请刷新后重试");
            }
        }
    }

    /**
     * 编辑销售订单（DRAFT / SUBMITTED 可编辑；SUBMITTED 含库存精确回算）
     * @param salesOrderId 销售订单ID
     * @param dto 编辑请求 DTO
     * @return 编辑后的销售订单详情VO
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public SalesOrderDetailVo update(Long salesOrderId, SalesOrderUpdateDto dto) {
        // 1. 锁前快速校验（不抢分布式锁）：主表 selectById + 版本/状态/字段非空/预计发货日期
        SalesOrder order = salesOrderMapper.selectById(salesOrderId);
        if (order == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
        validateVersion(order, dto.getVersion());
        String currentStatus = order.getStatus();
        boolean isDraft = SalesOrderStatus.DRAFT.name().equals(currentStatus);
        boolean isSubmitted = SalesOrderStatus.SUBMITTED.name().equals(currentStatus);
        if (!isDraft && !isSubmitted) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(),
                    "已审核或已产生出库事实的销售订单不允许编辑，当前状态: " + currentStatus);
        }
        if (isSubmitted && (dto.getExpectedDeliveryDate() == null || dto.getExpectedDeliveryDate().isBefore(LocalDate.now()))) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(),
                    "已提交的订单预计发货日期不能为空或早于今天");
        }
        Long customerId = IdUtil.parseRequiredLongId(dto.getCustomerId(), "客户ID");
        Long warehouseId = IdUtil.parseRequiredLongId(dto.getWarehouseId(), "出库仓库ID");
        // 1.1 防改仓库：仅 SUBMITTED 状态禁止改仓库（DRAFT 允许改，因为没锁定库存；SUBMITTED 跨仓库需释放旧仓库 + 锁定新仓库，单独 PR 实施）
        if (isSubmitted && !order.getWarehouseId().equals(warehouseId)) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                    "已锁定库存的销售订单不允许改仓库，请先取消订单再重新创建");
        }
        // 2. 校验客户/仓库存在 + 启用（共用私有方法）
        CustomerWarehouse cw = validateCustomerAndWarehouse(customerId, warehouseId);
        // 3. 批量查产品
        List<Long> productIds = dto.getItems().stream()
                .map(item -> IdUtil.parseRequiredLongId(item.getProductId(), "产品ID"))
                .toList();
        Map<Long, Product> productMap = productMapper.selectByIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
        // 4. 校验明细 + 累加总金额（重复产品 / 产品启用 / 数量精度）
        BigDecimal totalAmount = validateItemsAndComputeTotal(dto.getItems(), productMap);
        // 5. 分布式锁（DRAFT 和 SUBMITTED 都加，防与 submit / cancel / approve 并发）
        sourceOperationLockSupport.acquire(SourceType.SALES_ORDER.name(), salesOrderId);
        // 5.1 锁内重新查询 + 二次版本/状态校验（防 TOCTOU；给明确错误信息）
        SalesOrder reloaded = salesOrderMapper.selectById(salesOrderId);
        if (reloaded == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
        validateVersion(reloaded, dto.getVersion());
        String reloadedStatus = reloaded.getStatus();
        if (!SalesOrderStatus.DRAFT.name().equals(reloadedStatus)
                && !SalesOrderStatus.SUBMITTED.name().equals(reloadedStatus)) {
            String hint = SalesOrderStatus.CANCELLED.name().equals(reloadedStatus)
                    ? "订单已取消，无法编辑"
                    : "订单已审核/已出库，无法编辑";
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(),
                    hint + "，当前状态: " + reloadedStatus);
        }
        order = reloaded;
        boolean reloadedIsSubmitted = SalesOrderStatus.SUBMITTED.name().equals(reloadedStatus);
        // 6. 库存精确回算（仅 SUBMITTED；行锁 + 先释放后预占 + 校验可用 + updateById 校验）
        if (reloadedIsSubmitted) {
            List<SalesOrderItem> existingItems = salesOrderItemMapper.selectList(
                    new LambdaQueryWrapper<SalesOrderItem>()
                            .eq(SalesOrderItem::getSalesOrderId, salesOrderId)
            );
            Map<Long, Long> deltasByProduct = new HashMap<>();
            Map<Long, Long> oldLockedByProduct = existingItems.stream().collect(
                    Collectors.groupingBy(
                            SalesOrderItem::getProductId,
                            Collectors.summingLong(item -> item.getLockedQty() == null ? 0L : item.getLockedQty())));
            Map<Long, Long> newQtyByProduct = dto.getItems().stream().collect(
                    Collectors.groupingBy(
                            item -> IdUtil.parseRequiredLongId(item.getProductId(), "产品ID"),
                            Collectors.summingLong(item -> QtyUtil.toStored(item.getQuantity()))));
            Set<Long> allProductIds = new HashSet<>();
            allProductIds.addAll(oldLockedByProduct.keySet());
            allProductIds.addAll(newQtyByProduct.keySet());
            for (Long productId : allProductIds) {
                long oldLocked = oldLockedByProduct.getOrDefault(productId, 0L);
                long newQty = newQtyByProduct.getOrDefault(productId, 0L);
                long delta = newQty - oldLocked;
                if (delta != 0L) {
                    deltasByProduct.put(productId, delta);
                }
            }
            if (!deltasByProduct.isEmpty()) {
                warehouseStockReservationSupport.applyLockedQtyChanges(
                        order.getWarehouseId(), deltasByProduct, true, true);
            }
        }
        // 7. 主表 update（乐观锁 + 全字段 + 重算 totalAmount；先于明细操作，rows==0 早失败）
        SalesOrder update = new SalesOrder();
        update.setId(salesOrderId);
        update.setCustomerId(customerId);
        update.setCustomerCode(cw.customer().getCustomerCode());
        update.setCustomerName(cw.customer().getCustomerName());
        update.setWarehouseId(warehouseId);
        update.setWarehouseName(cw.warehouse().getWarehouseName());
        update.setExpectedDeliveryDate(dto.getExpectedDeliveryDate());
        update.setTotalAmount(QtyUtil.toStoredInt(totalAmount));
        update.setRemark(dto.getRemark());
        update.setVersion(dto.getVersion());
        int rows = salesOrderMapper.updateById(update);
        if (rows == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                    "数据已发生变化，请刷新后重试");
        }
        // 8. 物理删除现有明细（全删全插，无 diff 复杂度）
        List<SalesOrderItem> existingItemsForDelete = salesOrderItemMapper.selectList(
                new LambdaQueryWrapper<SalesOrderItem>()
                        .eq(SalesOrderItem::getSalesOrderId, salesOrderId)
        );
        if (!existingItemsForDelete.isEmpty()) {
            List<Long> idsToDelete = existingItemsForDelete.stream()
                    .map(SalesOrderItem::getId)
                    .toList();
            try {
                salesOrderItemService.removeByIds(idsToDelete);
            } catch (Exception e) {
                log.error("销售订单[{}]编辑异常:删除明细失败", order.getSalesNo(), e);
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                        "销售明细已被其他人修改，请刷新后重试");
            }
        }
        // 9. 保存新明细（SUBMITTED 时 lockedQty = quantity 继承原锁定量；DRAFT 时 = 0）
        List<SalesOrderItem> itemsToSave = new ArrayList<>();
        for (SalesOrderDraftItemDto itemDto : dto.getItems()) {
            Product product = productMap.get(IdUtil.parseRequiredLongId(itemDto.getProductId(), "产品ID"));
            itemsToSave.add(buildSalesOrderItem(itemDto, product, salesOrderId, order.getSalesNo(),
                    reloadedIsSubmitted ? QtyUtil.toStored(itemDto.getQuantity()) : 0L));
        }
        try {
            salesOrderItemService.saveBatch(itemsToSave);
        } catch (Exception e) {
            log.error("销售订单[{}]编辑异常:保存新明细失败", order.getSalesNo(), e);
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                    "销售明细保存失败，请刷新后重试");
        }
        return getDetail(salesOrderId);
    }

    /**
     * 生成 SALES_OUT 待确认出库单（同事务内调用；幂等检查避免重复生成）
     */
    private void generateSalesOutboundBill(SalesOrder order, List<SalesOrderItem> items) {
        // 幂等检查：同一销售订单已存在未确认出库单则跳过（与采购单保持一致，已确认/已取消的不拦，支持部分出库后再次生成）
        Long activeCount = outboundBillMapper.selectCount(
                new LambdaQueryWrapper<OutboundBill>()
                        .eq(OutboundBill::getSourceType, SourceType.SALES_ORDER.name())
                        .eq(OutboundBill::getSourceId, order.getId())
                        .in(OutboundBill::getStatus, StockBillStatus.DRAFT.name(), StockBillStatus.PENDING_CONFIRM.name())
        );
        if (activeCount != null && activeCount > 0) {
            log.warn("销售订单[{}]已存在未确认出库单，跳过生成", order.getSalesNo());
            return;
        }
        // 生成出库单号
        String outboundNo = billNoGenerator.nextNo(OutboundType.SALES_OUT.billNoPrefix());
        LoginUser loginUser = UserContext.requireCurrentUser();
        Long currentUserId = loginUser.getUserId();
        String currentUserName = loginUser.getRealName();
        // 构建出库单主表
        OutboundBill bill = new OutboundBill()
                .setOutboundNo(outboundNo)
                .setOutboundType(OutboundType.SALES_OUT.name())
                .setSourceType(SourceType.SALES_ORDER.name())
                .setSourceId(order.getId())
                .setSourceNo(order.getSalesNo())
                .setSourcePartyId(order.getCustomerId())
                .setSourcePartyName(order.getCustomerName())
                .setEntryMode(EntryMode.SOURCE_GENERATED.name())
                .setWarehouseId(order.getWarehouseId())
                .setWarehouseName(order.getWarehouseName())
                .setStatus(StockBillStatus.PENDING_CONFIRM.name())
                .setCreatedById(currentUserId)
                .setCreatedByName(currentUserName)
                .setResponsibleById(currentUserId)
                .setResponsibleByName(currentUserName)
                .setRemark(order.getRemark());
        outboundBillMapper.insert(bill);
        // 构建出库单明细
        List<OutboundBillItem> billItems = new ArrayList<>();
        for (SalesOrderItem item : items) {
            long planQty = item.getQuantity() == null ? 0L : item.getQuantity();
            long processedQty = item.getOutboundQty() == null ? 0L : item.getOutboundQty();
            long pendingQty = Math.max(0L, planQty - processedQty);
            billItems.add(new OutboundBillItem()
                    .setOutboundBillId(bill.getId())
                    .setOutboundNo(outboundNo)
                    .setSourceItemId(item.getId())
                    .setProductId(item.getProductId())
                    .setProductCode(item.getProductCode())
                    .setProductName(item.getProductName())
                    .setUnitName(item.getUnitName())
                    .setQuantityPrecision(item.getQuantityPrecision())
                    .setPlanQty(planQty)
                    .setProcessedQty(processedQty)
                    .setCurrentQty(0L)
                    .setPendingQty(pendingQty)
                    .setQualifiedQty(0L)
                    .setDefectiveQty(0L)
                    .setRemark(item.getRemark()));
        }
        outboundBillItemService.saveBatch(billItems);
    }

    /**
     * 实体转 VO，订单总金额从 100 倍存储值还原为业务小数
     */
    private SalesOrderVo toVo(SalesOrder entity) {
        SalesOrderVo vo = BeanUtil.copyProperties(entity, SalesOrderVo.class);
        vo.setSalesOrderId(entity.getId());
        vo.setTotalAmount(QtyUtil.toDecimal(entity.getTotalAmount()));
        return vo;
    }

    /**
     * 校验销售订单的乐观锁版本号是否匹配当前数据库值
     */
    private void validateVersion(SalesOrder order, Integer expectedVersion) {
        if (order.getVersion() == null || !order.getVersion().equals(expectedVersion)) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                    "数据已发生变化，请刷新后重试");
        }
    }

    /**
     * 校验销售订单的业务规则（客户/仓库/产品启用状态）
     */
    private void validateOrderBusiness(SalesOrder order, List<SalesOrderItem> items) {
        // 1. 校验客户/仓库存在 + 启用
        validateCustomerAndWarehouse(order.getCustomerId(), order.getWarehouseId());
        // 2. 校验明细中每个产品启用状态
        validateItemsStatus(items);
    }

    /**
     * 校验明细中每个产品启用状态（输入 SalesOrderItem entity，复用于 submit / approve / cancel 现有明细）
     */
    private void validateItemsStatus(List<SalesOrderItem> items) {
        // 1. 校验明细是否为空
        if (items == null || items.isEmpty()) {
            return;
        }
        // 2. 批量查询产品（避免循环查库）
        List<Long> productIds = items.stream()
                .map(SalesOrderItem::getProductId)
                .distinct()
                .toList();
        Map<Long, Product> productMap = productMapper.selectByIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
        // 3. 校验每个产品启用状态
        for (SalesOrderItem item : items) {
            Product product = productMap.get(item.getProductId());
            if (product == null) {
                throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(),
                        "产品不存在: " + item.getProductCode());
            }
            if (Integer.valueOf(0).equals(product.getStatus())) {
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                        "产品已停用，无法: " + product.getProductCode());
            }
        }
    }

    /**
     * 校验客户/仓库存在 + 启用（返回 customer + warehouse 实体供调用方使用，避免重复查询）
     */
    private CustomerWarehouse validateCustomerAndWarehouse(Long customerId, Long warehouseId) {
        Customer customer = customerMapper.selectById(customerId);
        if (customer == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "客户不存在");
        }
        if (Integer.valueOf(0).equals(customer.getStatus())) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "客户已停用，不能下单");
        }
        Warehouse warehouse = warehouseMapper.selectById(warehouseId);
        if (warehouse == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "出库仓库不存在");
        }
        if (Integer.valueOf(0).equals(warehouse.getStatus())) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "出库仓库已停用，不能下单");
        }
        return new CustomerWarehouse(customer, warehouse);
    }

    /**
     * 校验明细 + 累加总金额：重复产品校验、产品存在 + 启用、数量精度、累加 totalAmount
     * @return 累加后的总金额（业务小数，已 ×100 校验 setScale 2）
     */
    private BigDecimal validateItemsAndComputeTotal(List<SalesOrderDraftItemDto> items,
                                                   Map<Long, Product> productMap) {
        Set<Long> seenProductIds = new HashSet<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (SalesOrderDraftItemDto itemDto : items) {
            Long productId = IdUtil.parseRequiredLongId(itemDto.getProductId(), "产品ID");
            if (!seenProductIds.add(productId)) {
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                        "同一产品不能重复添加: " + itemDto.getProductId());
            }
            Product product = productMap.get(productId);
            if (product == null) {
                throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(),
                        "产品不存在: " + itemDto.getProductId());
            }
            if (Integer.valueOf(0).equals(product.getStatus())) {
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                        "产品已停用，不能下单: " + product.getProductCode());
            }
            validateQuantityPrecision(itemDto.getQuantity(), product.getQuantityPrecision(),
                    product.getProductCode());
            BigDecimal lineAmount = itemDto.getQuantity().multiply(itemDto.getUnitPrice())
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            totalAmount = totalAmount.add(lineAmount);
        }
        return totalAmount;
    }

    /**
     * 构建销售订单明细实体（createDraft / update 共用，lockedQty 由调用方控制）
     */
    private SalesOrderItem buildSalesOrderItem(SalesOrderDraftItemDto itemDto, Product product,
                                             Long salesOrderId, String salesNo, Long lockedQty) {
        BigDecimal lineAmount = itemDto.getQuantity().multiply(itemDto.getUnitPrice())
                .setScale(2, java.math.RoundingMode.HALF_UP);
        return new SalesOrderItem()
                .setSalesOrderId(salesOrderId)
                .setSalesNo(salesNo)
                .setProductId(product.getId())
                .setProductCode(product.getProductCode())
                .setProductName(product.getProductName())
                .setUnitName(product.getUnitName())
                .setQuantityPrecision(product.getQuantityPrecision())
                .setQuantity(QtyUtil.toStored(itemDto.getQuantity()))
                .setLockedQty(lockedQty)
                .setOutboundQty(0L)
                .setUnitPrice(QtyUtil.toStoredInt(itemDto.getUnitPrice()))
                .setTotalAmount(QtyUtil.toStoredInt(lineAmount))
                .setRemark(itemDto.getRemark());
    }

    /**
     * 明细实体转 VO，数量与金额字段均从 100 倍存储值还原为业务小数
     */
    private SalesOrderItemVo toItemVo(SalesOrderItem entity) {
        SalesOrderItemVo vo = BeanUtil.copyProperties(entity, SalesOrderItemVo.class);
        vo.setSalesOrderItemId(entity.getId());
        vo.setQuantity(QtyUtil.toDecimal(entity.getQuantity()));
        vo.setLockedQty(QtyUtil.toDecimal(entity.getLockedQty()));
        vo.setOutboundQty(QtyUtil.toDecimal(entity.getOutboundQty()));
        vo.setUnitPrice(QtyUtil.toDecimal(entity.getUnitPrice()));
        vo.setTotalAmount(QtyUtil.toDecimal(entity.getTotalAmount()));
        return vo;
    }

    /**
     * 校验销售数量的小数位不得超过产品数量精度
     */
    private void validateQuantityPrecision(BigDecimal quantity, Integer quantityPrecision, String productCode) {
        if (quantity == null || quantityPrecision == null) {
            return;
        }
        // stripTrailingZeros 后小数位 0 表示整数
        BigDecimal normalized = quantity.stripTrailingZeros();
        int scale = normalized.scale();
        // stripTrailingZeros 对整数会返回 scale < 0 的情况（如 100 → scale=-2），需 clamp 到 0
        if (scale < 0) {
            scale = 0;
        }
        if (scale > quantityPrecision) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                    "产品 " + productCode + " 数量小数位不得超过 " + quantityPrecision + " 位");
        }
    }
}