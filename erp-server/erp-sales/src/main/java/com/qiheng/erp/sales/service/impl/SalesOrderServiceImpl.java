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
import com.qiheng.erp.warehouse.domain.warehouse.entity.Warehouse;
import com.qiheng.erp.warehouse.domain.warehousestock.entity.WarehouseStock;
import com.qiheng.erp.warehouse.mapper.WarehouseMapper;
import com.qiheng.erp.warehouse.service.IWarehouseStockService;
import com.qiheng.erp.warehouse.service.support.WarehouseStockLockSupport;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
public class SalesOrderServiceImpl extends ServiceImpl<SalesOrderMapper, SalesOrder> implements ISalesOrderService {
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
        // 1. 校验客户存在 + 启用
        Customer customer = customerMapper.selectById(customerId);
        if (customer == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "客户不存在");
        }
        if (Integer.valueOf(0).equals(customer.getStatus())) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "客户已停用，不能下单");
        }
        // 2. 校验出库仓库存在 + 启用
        Warehouse warehouse = warehouseMapper.selectById(warehouseId);
        if (warehouse == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "出库仓库不存在");
        }
        if (Integer.valueOf(0).equals(warehouse.getStatus())) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "出库仓库已停用，不能下单");
        }
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
        // 5. 校验明细 + 同时累加总金额 + 构建明细实体
        Set<Long> seenProductIds = new HashSet<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<SalesOrderItem> items = new ArrayList<>();
        for (SalesOrderDraftItemDto itemDto : dto.getItems()) {
            Long productId = IdUtil.parseRequiredLongId(itemDto.getProductId(), "产品ID");
            // 重复产品校验
            if (!seenProductIds.add(productId)) {
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                        "同一产品不能重复添加: " + itemDto.getProductId());
            }
            // 校验产品存在 + 启用
            Product product = productMap.get(productId);
            if (product == null) {
                throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "产品不存在: " + itemDto.getProductId());
            }
            if (Integer.valueOf(0).equals(product.getStatus())) {
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                        "产品已停用，不能下单: " + product.getProductCode());
            }
            // 数量精度校验：业务小数位不得超过 product.quantityPrecision
            validateQuantityPrecision(itemDto.getQuantity(), product.getQuantityPrecision(),
                    product.getProductCode());
            BigDecimal lineAmount = itemDto.getQuantity().multiply(itemDto.getUnitPrice())
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            totalAmount = totalAmount.add(lineAmount);
            SalesOrderItem item = new SalesOrderItem();
            item.setSalesNo(salesNo);
            item.setProductId(productId);
            item.setProductCode(product.getProductCode());
            item.setProductName(product.getProductName());
            item.setUnitName(product.getUnitName());
            item.setQuantityPrecision(product.getQuantityPrecision());
            item.setQuantity(QtyUtil.toStored(itemDto.getQuantity()));
            item.setLockedQty(0L);
            item.setOutboundQty(0L);
            item.setUnitPrice(QtyUtil.toStoredInt(itemDto.getUnitPrice()));
            item.setTotalAmount(QtyUtil.toStoredInt(lineAmount));
            item.setRemark(itemDto.getRemark());
            items.add(item);
        }
        // 6. 插入主表
        SalesOrder order = new SalesOrder();
        order.setSalesNo(salesNo);
        order.setCustomerId(customerId);
        order.setCustomerCode(customer.getCustomerCode());
        order.setCustomerName(customer.getCustomerName());
        order.setWarehouseId(warehouseId);
        order.setWarehouseName(warehouse.getWarehouseName());
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
        if (!order.getVersion().equals(version)) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                    "数据已发生变化，请刷新后重试");
        }
        // 2. 校验预计发货日期非空且不早于今天
        if (order.getExpectedDeliveryDate() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "预计发货日期不能为空");
        }
        if (order.getExpectedDeliveryDate().isBefore(LocalDate.now())) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "预计发货日期不能早于今天");
        }
        // 3. 查明细 + 提取待锁定产品 ID（去重排序，配合 WarehouseStockLockSupport 避免多产品死锁）
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
        List<Long> productIds = items.stream()
                .map(SalesOrderItem::getProductId)
                .distinct()
                .sorted()
                .toList();
        // 4. 数据库行锁：SELECT ... FOR UPDATE 同仓库的所有相关产品库存
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
            // 累加明细的已锁定数量
            long itemLocked = (item.getLockedQty() == null ? 0L : item.getLockedQty()) + need;
            item.setLockedQty(itemLocked);
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
     * 实体转 VO，订单总金额从 100 倍存储值还原为业务小数
     */
    private SalesOrderVo toVo(SalesOrder entity) {
        SalesOrderVo vo = BeanUtil.copyProperties(entity, SalesOrderVo.class);
        vo.setSalesOrderId(entity.getId());
        vo.setTotalAmount(QtyUtil.toDecimal(entity.getTotalAmount()));
        return vo;
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