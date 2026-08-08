package com.qiheng.erp.returnorder.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.util.BillNoGenerator;
import com.qiheng.erp.common.util.IdUtil;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.returnorder.domain.dto.ReturnOrderCreateDto;
import com.qiheng.erp.returnorder.domain.dto.ReturnOrderItemCreateDto;
import com.qiheng.erp.returnorder.domain.dto.ReturnOrderUpdateDto;
import com.qiheng.erp.returnorder.domain.port.ReturnSourceItem;
import com.qiheng.erp.returnorder.domain.port.ReturnSourceOrder;
import com.qiheng.erp.returnorder.domain.port.ReturnSourceProvider;
import com.qiheng.erp.returnorder.domain.port.ReturnType;
import com.qiheng.erp.returnorder.domain.dto.ReturnOrderPageDto;
import com.qiheng.erp.returnorder.domain.entity.ReturnOrder;
import com.qiheng.erp.returnorder.domain.entity.ReturnOrderItem;
import com.qiheng.erp.returnorder.domain.enums.ReturnStatus;
import com.qiheng.erp.returnorder.domain.vo.ReturnOrderDetailVo;
import com.qiheng.erp.returnorder.domain.vo.ReturnOrderItemVo;
import com.qiheng.erp.returnorder.domain.vo.ReturnOrderVo;
import com.qiheng.erp.returnorder.domain.vo.ReturnableSourceOrderVo;
import com.qiheng.erp.returnorder.mapper.ReturnOrderItemMapper;
import com.qiheng.erp.returnorder.mapper.ReturnOrderMapper;
import com.qiheng.erp.returnorder.service.IReturnOrderItemService;
import com.qiheng.erp.returnorder.service.IReturnOrderService;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.warehouse.domain.outbound.entity.OutboundBill;
import com.qiheng.erp.warehouse.domain.outbound.entity.OutboundBillItem;
import com.qiheng.erp.warehouse.domain.warehousestock.entity.WarehouseStock;
import com.qiheng.erp.warehouse.mapper.WarehouseStockMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 统一退货单服务实现。
 *
 * <p>该服务维护退货单自身的数据和处理进度。采购、销售来源订单由
 * {@link ReturnSourceProvider} 提供，避免退货模块反向依赖具体业务模块。</p>
 */
@Service
@Slf4j
public class ReturnOrderServiceImpl extends ServiceImpl<ReturnOrderMapper, ReturnOrder> implements IReturnOrderService {
    @Autowired
    private ReturnOrderMapper returnOrderMapper;
    @Autowired
    private ReturnOrderItemMapper returnOrderItemMapper;
    @Autowired
    private IReturnOrderItemService returnOrderItemService;
    @Autowired
    private List<ReturnSourceProvider> sourceProviders;
    @Autowired
    private WarehouseStockMapper warehouseStockMapper;
    @Autowired
    private BillNoGenerator billNoGenerator;
    @Autowired
    private RedissonClient redissonClient;

    /**
     * 分页查询退货单主信息。
     *
     * <p>查询前校验对应来源 Provider 已部署，避免销售后端尚未接入时返回不完整数据。</p>
     *
     * @param dto 查询参数
     * @return 分页结果
     */
    @Override
    public PageResult<ReturnOrderVo> page(ReturnOrderPageDto dto) {
        // 校验参数是否正确
        ReturnType returnType = requireType(dto.getReturnType());
        requireProvider(returnType);
        Long partyId = IdUtil.parseOptionalLongId(dto.getPartyId(), "往来单位ID");
        Long warehouseId = IdUtil.parseOptionalLongId(dto.getWarehouseId(), "仓库ID");
        String status = dto.getStatus() == null ? null : dto.getStatus().name();
        // 分页查询退货单主信息
        Page<ReturnOrder> page = this.page(dto.toPage(), new LambdaQueryWrapper<ReturnOrder>()
                .eq(ReturnOrder::getReturnType, returnType.name())
                .like(StrUtil.isNotBlank(dto.getReturnNo()), ReturnOrder::getReturnNo, dto.getReturnNo())
                .like(StrUtil.isNotBlank(dto.getSourceOrderNo()), ReturnOrder::getSourceOrderNo, dto.getSourceOrderNo())
                .eq(partyId != null, ReturnOrder::getPartyId, partyId)
                .eq(warehouseId != null, ReturnOrder::getWarehouseId, warehouseId)
                .eq(StrUtil.isNotBlank(status), ReturnOrder::getStatus, status)
                .orderByDesc(ReturnOrder::getCreateTime));
        return PageResult.of(page.getRecords().stream().map(this::toVo).toList(),
                (int) page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }

    /**
     * 查询退货单详情
     * @param returnOrderId 退货单 ID
     * @return 退货单详情
     */
    @Override
    public ReturnOrderDetailVo getDetail(Long returnOrderId) {
        // 校验参数是否正确
        ReturnOrder order = returnOrderMapper.selectById(returnOrderId);
        if (order == null) throw new BizException(ErrorCode.DATA_NOT_FOUND);
        requireProvider(parseType(order.getReturnType()));
        // 查询退货单明细
        List<ReturnOrderItem> items = returnOrderItemMapper.selectList(new LambdaQueryWrapper<ReturnOrderItem>()
                .eq(ReturnOrderItem::getReturnOrderId, returnOrderId).orderByAsc(ReturnOrderItem::getId));
        ReturnOrderDetailVo detail = new ReturnOrderDetailVo();
        BeanUtil.copyProperties(toVo(order), detail);
        // 查看详情只展示本单历史事实；剩余可退数量仅在新增、编辑来源明细时查询。
        detail.setItems(items.stream().map(this::toItemVo).toList());
        return detail;
    }

    /**
     * 查询退货单来源单详情。
     * @param returnType 退货单类型。
     * @param sourceOrderId 来源单ID。
     * @return 退货单来源单详情。
     */
    @Override
    public List<ReturnOrderItemVo> listSourceItems(ReturnType returnType, Long sourceOrderId) {
        ReturnSourceProvider provider = requireProvider(returnType);
        // 1. 查询来源订单
        ReturnSourceOrder sourceOrder = provider.getSourceOrder(sourceOrderId);
        // 2. 计算占用数量（排除自身）
        Map<Long, Integer> occupied = buildOccupiedMap(returnType, sourceOrderId, null);
        // 3. 查询来源订单明细
        List<ReturnSourceItem> sourceItems = provider.listSourceItems(List.of(sourceOrderId))
                .getOrDefault(sourceOrderId, List.of());
        if (sourceItems.isEmpty()) return List.of();
        // 4. 构建退货单来源单详情
        List<Long> productIds = sourceItems.stream().map(ReturnSourceItem::productId).toList();
        // 5. 查询库存可用数量
        Map<Long, Long> stockAvailableMap = loadStockAvailable(returnType, sourceOrder.warehouseId(), productIds);
        return sourceItems.stream().map(source -> {
            // 查询来源单已入库数量
            int fulfilled = source.fulfilledQty() == null ? 0 : source.fulfilledQty();
            // 查询已占用数量（排除自身,草稿和取消状态订单, 即已退货数量）
            int used = occupied.getOrDefault(source.sourceOrderItemId(), 0);
            // 查询来源可退数量(已入库数量-已退货数量)
            int sourceAvailable = Math.max(0, fulfilled - used);
            // 查询库存可用数量
            long stockAvail = stockAvailableMap.getOrDefault(source.productId(), 0L);
            // 查询最终可退数量（采购退货受库存限制，销售退货不受限）
            int available = calculateAvailable(returnType, sourceAvailable, stockAvail);
            ReturnOrderItemVo vo = new ReturnOrderItemVo();
            vo.setSourceOrderItemId(source.sourceOrderItemId());
            vo.setProductId(source.productId());
            vo.setProductCode(source.productCode());
            vo.setProductName(source.productName());
            vo.setUnitName(source.unitName());
            vo.setQuantityPrecision(source.quantityPrecision());
            vo.setSourceFulfilledQty(toDecimal(fulfilled));
            vo.setOccupiedQty(toDecimal(used));
            vo.setStockAvailableQty(toDecimal((int) Math.max(0L, stockAvail)));
            vo.setUnitPrice(toDecimal(source.unitPrice()));
            vo.setAvailableReturnQty(toDecimal(available));
            return vo;
        }).toList();
    }

    /**
     * 创建退货单草稿。
     * @param dto 创建请求
     * @return 退货单详情
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReturnOrderDetailVo createDraft(ReturnOrderCreateDto dto) {
        // 1. 校验参数是否正确
        ReturnType returnType = requireType(dto.getReturnType());
        Long sourceOrderId = IdUtil.parseRequiredLongId(dto.getSourceOrderId(), "来源订单ID");
        Long warehouseId = IdUtil.parseRequiredLongId(dto.getWarehouseId(), "退货仓库ID");
        // 2. 校验预计执行日期是否正确
        validateExpectedExecutionDate(dto.getExpectedExecutionDate());
        // 3. 查询来源订单上下文(来源订单快照,来源单id明细映射表,产品库存可用数量映射表)
        SourceContext ctx = resolveSourceContext(returnType, sourceOrderId);
        if (!warehouseId.equals(ctx.sourceOrder.warehouseId())) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "退货仓库必须与原订单仓库一致");
        }
        // 4. 计算占用数量（排除自身）
        Map<Long, Integer> occupied = buildOccupiedMap(returnType, sourceOrderId, null);
        // 5. 构建退货单明细(退货单明细列表,退货总金额),同时校验申请退回数量以及精度是否符合要求
        BuiltItems built = buildReturnItems(returnType, ctx.sourceItemMap, dto.getItems(), occupied, ctx.stockAvailableMap, null);
        // 6. 获取当前用户信息
        LoginUser loginUser = UserContext.requireCurrentUser();
        // 7. 生成退货单编号
        String returnNo = billNoGenerator.nextNo(returnType == ReturnType.PURCHASE_RETURN ? "PR" : "SR");
        // 8. 构建退货单主表信息
        ReturnOrder order = new ReturnOrder()
                .setReturnNo(returnNo)
                .setReturnType(returnType.name())
                .setSourceOrderId(sourceOrderId)
                .setSourceOrderNo(ctx.sourceOrder.sourceOrderNo())
                .setPartyId(ctx.sourceOrder.partyId())
                .setPartyCode(ctx.sourceOrder.partyCode())
                .setPartyName(ctx.sourceOrder.partyName())
                .setWarehouseId(warehouseId)
                .setWarehouseName(ctx.sourceOrder.warehouseName())
                .setExpectedExecutionDate(dto.getExpectedExecutionDate())
                .setHandlingType(dto.getHandlingType())
                .setReasonCode(dto.getReasonCode())
                .setReturnReason(dto.getReturnReason())
                .setTotalAmount(QtyUtil.toStored(built.totalAmount).intValue())
                .setStatus(ReturnStatus.DRAFT.name())
                .setStatusReason("")
                .setCreatedById(loginUser.getUserId())
                .setCreatedByName(loginUser.getRealName())
                .setRemark(dto.getRemark());
        returnOrderMapper.insert(order);
        built.items.forEach(item -> item.setReturnOrderId(order.getId()));
        returnOrderItemService.saveBatch(built.items);
        return getDetail(order.getId());
    }

    /**
     * 编辑退货单草稿（DRAFT/SUBMITTED 可编辑），全量替换明细。
     * @param returnOrderId 退货单ID
     * @param dto 编辑请求
     * @return 退货单详情
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReturnOrderDetailVo update(Long returnOrderId, ReturnOrderUpdateDto dto) {
        // 1. 校验状态是否可编辑以及版本号是否正确
        ReturnOrder existing = loadAndCheckStatus(returnOrderId, dto.getVersion(),
                ReturnStatus.DRAFT, ReturnStatus.SUBMITTED);
        // 2. 校验预计执行日期是否正确
        validateExpectedExecutionDate(dto.getExpectedExecutionDate());
        // 3. 校验参数是否正确
        ReturnType returnType = parseType(existing.getReturnType());
        // 4. 校验前端传递的ID是否正确
        Long sourceOrderId = IdUtil.parseRequiredLongId(dto.getSourceOrderId(), "来源订单ID");
        Long warehouseId = IdUtil.parseRequiredLongId(dto.getWarehouseId(), "退货仓库ID");
        // 5. 构建来源订单上下文(来源订单快照,来源单ID -> 明细映射表,产品ID -> 库存可用数量映射表)
        SourceContext ctx = resolveSourceContext(returnType, sourceOrderId);
        if (!warehouseId.equals(ctx.sourceOrder.warehouseId())) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "退货仓库必须与原订单仓库一致");
        }
        // 6. 计算占用数量（排除自身）
        Map<Long, Integer> occupied = buildOccupiedMap(returnType, sourceOrderId, returnOrderId);
        // 7. 构建退货单明细(退货单明细列表,退货总金额),同时校验申请退回数量以及精度是否符合要求
        BuiltItems built = buildReturnItems(returnType, ctx.sourceItemMap, dto.getItems(), occupied, ctx.stockAvailableMap, returnOrderId);
        // 8. 更新退货单主表信息
        ReturnOrder update = new ReturnOrder();
        update.setId(returnOrderId);
        update.setSourceOrderId(sourceOrderId);
        update.setSourceOrderNo(ctx.sourceOrder.sourceOrderNo());
        update.setPartyId(ctx.sourceOrder.partyId());
        update.setPartyCode(ctx.sourceOrder.partyCode());
        update.setPartyName(ctx.sourceOrder.partyName());
        update.setWarehouseId(warehouseId);
        update.setWarehouseName(ctx.sourceOrder.warehouseName());
        update.setExpectedExecutionDate(dto.getExpectedExecutionDate());
        update.setHandlingType(dto.getHandlingType());
        update.setReasonCode(dto.getReasonCode());
        update.setReturnReason(dto.getReturnReason());
        update.setTotalAmount(QtyUtil.toStored(built.totalAmount).intValue());
        update.setRemark(dto.getRemark());
        update.setVersion(dto.getVersion());
        // 9. 更新退货单主表信息
        int rows = returnOrderMapper.updateById(update);
        if (rows == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "数据已被他人修改，请刷新后重试");
        }
        // 10. 删除旧的退货单明细
        returnOrderItemMapper.delete(new LambdaQueryWrapper<ReturnOrderItem>()
                .eq(ReturnOrderItem::getReturnOrderId, returnOrderId));
        // 11. 保存新的退货单明细
        returnOrderItemService.saveBatch(built.items);
        // 12. 返回退货单详情
        return getDetail(returnOrderId);
    }

    /**
     * 删除退货单草稿（仅 DRAFT 可删除），同一事务内删除明细。
     * @param returnOrderId 退货单ID
     * @param version 乐观锁版本号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long returnOrderId, Integer version) {
        // 1. 校验退货单是否存在、状态是否为草稿、乐观锁版本是否正确
        ReturnOrder existing = loadAndCheckStatus(returnOrderId, version, ReturnStatus.DRAFT);
        // 2. 逻辑删除退货单主表（带乐观锁版本条件，防止并发覆盖）
        int rows = returnOrderMapper.delete(new LambdaQueryWrapper<ReturnOrder>()
                .eq(ReturnOrder::getId, returnOrderId)
                .eq(ReturnOrder::getVersion, existing.getVersion()));
        if (rows == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "数据已被他人修改，请刷新后重试");
        }
        // 3. 物理删除退货单明细（明细表无逻辑删除字段，与编辑接口保持一致）
        returnOrderItemMapper.delete(new LambdaQueryWrapper<ReturnOrderItem>()
                .eq(ReturnOrderItem::getReturnOrderId, returnOrderId));
    }

    /**
     * 提交退货单草稿（DRAFT -> SUBMITTED）。
     * <p>按来源订单加分布式锁串行化同一来源的退货提交，防止并发超额退货；
     * 重复请求（SUBMITTED 状态）幂等返回成功。</p>
     * @param returnOrderId 退货单ID
     * @param version 乐观锁版本号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long returnOrderId, Integer version) {
        // 1. 查询退货单，获取来源订单ID用于加锁
        ReturnOrder existing = returnOrderMapper.selectById(returnOrderId);
        if (existing == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "退货单不存在");
        }
        // 2. 快速幂等：已提交状态直接返回，避免重复请求抢锁
        if (ReturnStatus.SUBMITTED.name().equals(existing.getStatus())) {
            return;
        }
        // 3. 按来源订单加分布式锁，串行化同一来源的退货提交，防止并发超额退货
        RLock lock = redissonClient.getLock("return:source:" + existing.getSourceOrderId());
        boolean acquired;
        try {
            acquired = lock.tryLock(5, 30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "操作被中断");
        }
        if (!acquired) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "该来源订单正在被其他退货操作处理，请稍后再试");
        }
        // 4. 注册事务同步：事务提交/回滚后释放锁，确保锁覆盖整个事务
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        });
        // 5. 加锁后重新查询退货单，基于最新数据做幂等和状态判断（锁外查询可能已过时）
        existing = returnOrderMapper.selectById(returnOrderId);
        if (existing == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "退货单不存在");
        }
        if (ReturnStatus.SUBMITTED.name().equals(existing.getStatus())) {
            return;
        }
        // 6. 校验状态：仅草稿状态可提交
        if (!ReturnStatus.DRAFT.name().equals(existing.getStatus())) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "仅草稿状态可提交");
        }
        // 7. 校验乐观锁版本号
        if (version != null && !existing.getVersion().equals(version)) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "数据已被他人修改，请刷新后重试");
        }
        // 8. 校验预计执行日期：必填且不早于当天
        if (existing.getExpectedExecutionDate() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "预计执行日期不能为空");
        }
        validateExpectedExecutionDate(existing.getExpectedExecutionDate());
        // 9. 重新校验剩余可退数量（锁内数据最新，防止并发超额退货）
        ReturnType returnType = parseType(existing.getReturnType());
        revalidateAvailable(returnType, returnOrderId, existing.getSourceOrderId());
        // 10. 更新退货单状态为已提交，带乐观锁
        ReturnOrder update = new ReturnOrder();
        update.setId(returnOrderId);
        update.setStatus(ReturnStatus.SUBMITTED.name());
        update.setSubmittedAt(LocalDateTime.now());
        update.setVersion(version);
        int rows = returnOrderMapper.updateById(update);
        if (rows == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "数据已被他人修改，请刷新后重试");
        }
    }

    /**
     * 提交时重新校验明细的可退数量。
     * <p>草稿创建后，其他退货单可能占用来源可退量，提交时必须重新聚合校验，
     * 避免并发超额退货。</p>
     * @param returnType 退货类型
     * @param returnOrderId 退货单ID
     * @param sourceOrderId 来源订单ID
     */
    private void revalidateAvailable(ReturnType returnType, Long returnOrderId, Long sourceOrderId) {
        // 1. 获取来源上下文（来源订单快照 + 来源明细映射 + 库存可用映射）
        SourceContext ctx = resolveSourceContext(returnType, sourceOrderId);
        // 2. 计算占用数量（排除自身，当前为草稿本就不占用，传 returnOrderId 语义明确）
        Map<Long, Integer> occupied = buildOccupiedMap(returnType, sourceOrderId, returnOrderId);
        // 3. 查询退货单明细
        List<ReturnOrderItem> items = returnOrderItemMapper.selectList(new LambdaQueryWrapper<ReturnOrderItem>()
                .eq(ReturnOrderItem::getReturnOrderId, returnOrderId));
        // 4. 逐条校验申请数量是否超过剩余可退数量
        for (ReturnOrderItem item : items) {
            ReturnSourceItem source = ctx.sourceItemMap.get(item.getSourceOrderItemId());
            if (source == null) {
                throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "来源明细不存在: " + item.getSourceOrderItemId());
            }
            int fulfilled = source.fulfilledQty() == null ? 0 : source.fulfilledQty();
            int used = occupied.getOrDefault(item.getSourceOrderItemId(), 0);
            int sourceAvailable = Math.max(0, fulfilled - used);
            long stockAvail = ctx.stockAvailableMap.getOrDefault(item.getProductId(), 0L);
            int available = calculateAvailable(returnType, sourceAvailable, stockAvail);
            int requested = item.getRequestedQty() == null ? 0 : item.getRequestedQty();
            if (requested > available) {
                String detail = returnType == ReturnType.PURCHASE_RETURN
                        ? String.format("来源可退 %s，仓库可用库存 %s", toDecimal(sourceAvailable), toDecimal((int) Math.max(0L, stockAvail)))
                        : String.format("来源可退 %s", toDecimal(sourceAvailable));
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(),
                        "申请退回数量 " + toDecimal(requested) + " 超过剩余可退数量 " + toDecimal(available) + "（" + detail + "）");
            }
        }
    }

    /** 校验申请数量的小数位不超过产品精度。 */
    private void validateQuantityPrecision(BigDecimal qty, int precision) {
        if (qty == null) return;
        int scale = qty.stripTrailingZeros().scale();
        // 整数经过 stripTrailingZeros 后 scale 可能为负数，归零处理
        if (scale < 0) scale = 0;
        if (scale > precision) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "数量小数位不得超过" + precision + "位");
        }
    }

    /** 校验预计执行日期不为空时不早于当天。 */
    private void validateExpectedExecutionDate(java.time.LocalDate date) {
        if (date != null && date.isBefore(java.time.LocalDate.now())) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "预计执行日期不得早于当天");
        }
    }

    /**
     * 查询退货单来源单。
     * @param returnType 退货单类型。
     * @param sourceOrderNo 来源单编号。
     * @param limit 最大返回数量。
     * @return 退货单来源单。
     */
    @Override
    public List<ReturnableSourceOrderVo> searchSourceOrders(ReturnType returnType, String sourceOrderNo, int limit) {
        ReturnSourceProvider provider = requireProvider(returnType);
        List<ReturnSourceOrder> sources = provider.searchSourceOrders(sourceOrderNo, limit);
        if (sources.isEmpty()) return List.of();
        // 1. 批量获取所有来源单的明细（按来源单ID -> 来源明细列表）
        List<Long> sourceOrderIds = sources.stream().map(ReturnSourceOrder::sourceOrderId).toList();
        Map<Long, List<ReturnSourceItem>> sourceItemsMap = provider.listSourceItems(sourceOrderIds);
        // 2. 批量查询占用数量（来源单ID -> 来源明细ID -> 占用数量）
        Map<Long, Map<Long, Integer>> occupiedMap = buildOccupiedMapBatch(returnType, sourceOrderIds);
        // 3. 按仓库对来源单中的产品ID进行去重分组（仓库ID -> 产品ID列表）
        Map<Long, Set<Long>> warehouseProducts = new HashMap<>();
        for (ReturnSourceOrder src : sources) {
            // 3.1. 获取当前来源单的明细
            List<ReturnSourceItem> items = sourceItemsMap.get(src.sourceOrderId());
            // 3.2. 合并当前来源单的明细到仓库库存中
            if (items != null) {
                items.forEach(item -> warehouseProducts
                        .computeIfAbsent(src.warehouseId(), k -> new HashSet<>()).add(item.productId())
                );
            }
        }
        // 3.3. 批量查询可用库存(
        Map<Long, Map<Long, Long>> stockCache = new HashMap<>();
        for (Map.Entry<Long, Set<Long>> entry : warehouseProducts.entrySet()) {
            stockCache.put(entry.getKey(), loadStockAvailable(returnType, entry.getKey(), List.copyOf(entry.getValue())));
        }
        // 4. 计算每个来源单的可退数量
        return sources.stream().map(source -> {
            // 4.1. 获取当前来源单的明细
            List<ReturnSourceItem> items = sourceItemsMap.getOrDefault(source.sourceOrderId(), List.of());
            // 4.2. 获取当前来源单的占用数量
            Map<Long, Integer> occupied = occupiedMap.getOrDefault(source.sourceOrderId(), Map.of());
            // 4.3. 获取当前仓库的产品的可用库存
            Map<Long, Long> stockAvailMap = stockCache.getOrDefault(source.warehouseId(), Map.of());
            // 4.4. 计算当前来源单的可退数量
            BigDecimal totalAvailable = BigDecimal.ZERO;
            int fulfilledCount = 0;
            for (ReturnSourceItem sourceItem : items) {
                // 获取当前来源单明细的已入库数量
                int fulfilled = sourceItem.fulfilledQty() == null ? 0 : sourceItem.fulfilledQty();
                // 获取当前来源单明细的占用数量(已退数量)
                int used = occupied.getOrDefault(sourceItem.sourceOrderItemId(), 0);
                // 计算当前来源单明细的可退数量(已入库数量-已退数量)
                int sourceAvailable = Math.max(0, fulfilled - used);
                // 获取当前来源单明细的可用库存
                long stockAvail = stockAvailMap.getOrDefault(sourceItem.productId(), 0L);
                // 计算最终可退数量（采购退货受库存限制，销售退货不受限）
                int available = calculateAvailable(returnType, sourceAvailable, stockAvail);
                if (available > 0) {
                    fulfilledCount++;
                    totalAvailable = totalAvailable.add(toDecimal(available));
                }
            }
            // 4.5. 构建当前来源单的可退数量VO
            ReturnableSourceOrderVo vo = new ReturnableSourceOrderVo();
            vo.setSourceOrderId(source.sourceOrderId());
            vo.setSourceOrderNo(source.sourceOrderNo());
            vo.setPartyId(source.partyId());
            vo.setPartyCode(source.partyCode());
            vo.setPartyName(source.partyName());
            vo.setWarehouseId(source.warehouseId());
            vo.setWarehouseName(source.warehouseName());
            vo.setFulfilledItemCount(fulfilledCount);
            vo.setTotalAvailableReturnQty(totalAvailable);
            return vo;
        }).filter(item -> item.getFulfilledItemCount() > 0).toList();
    }

    /**
     * 仓储确认采购退货出库后，同一事务内回写退货单进度。
     * @param bill 采购退货出库单。
     * @param items 采购退货出库单明细。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleOutboundConfirmation(OutboundBill bill, List<OutboundBillItem> items) {
        // 1. 校验采购退货出库单是否关联有效退货单
        ReturnOrder order = returnOrderMapper.selectById(bill.getSourceId());
        if (order == null || parseType(order.getReturnType()) != ReturnType.PURCHASE_RETURN) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "采购退货出库单未关联有效退货单");
        }
        // 2. 校验退货单状态是否允许出库确认
        ReturnStatus currentStatus = ReturnStatus.valueOf(order.getStatus());
        if (currentStatus != ReturnStatus.APPROVED && currentStatus != ReturnStatus.PARTIAL_EXECUTED) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "当前退货单状态不允许出库确认");
        }
        // 3. 构建退货单明细映射表(退货单明细ID -> 退货单明细)
        Map<Long, ReturnOrderItem> returnItems = returnOrderItemMapper.selectList(new LambdaQueryWrapper<ReturnOrderItem>()
            .eq(ReturnOrderItem::getReturnOrderId, order.getId())).stream()
            .collect(Collectors.toMap(ReturnOrderItem::getId, item -> item));
        // 4. 校验采购退货出库单明细是否关联有效退货单明细
        for (OutboundBillItem outboundItem : items) {
            ReturnOrderItem returnItem = returnItems.get(outboundItem.getSourceItemId());
            if (returnItem == null || outboundItem.getCurrentQty() == null) {
                throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "采购退货出库明细未关联有效退货明细");
            }
            // 计算此次出库量+已退库数量是否超过已审核数量
            int approved = returnItem.getApprovedQty() == null ? 0 : returnItem.getApprovedQty();
            int processed = returnItem.getProcessedQty() == null ? 0 : returnItem.getProcessedQty();
            long nextProcessed = (long) processed + outboundItem.getCurrentQty();
            if (nextProcessed > approved) {
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "采购退货出库数量超过已审核数量");
            }
            returnItem.setProcessedQty((int) nextProcessed);
        }
        // 5. 判断退货单是否完成并更新主表状态
        boolean completed = returnItems.values().stream().allMatch(item ->
                (item.getApprovedQty() == null ? 0 : item.getApprovedQty()) == (item.getProcessedQty() == null ? 0 : item.getProcessedQty()));
        order.setStatus(completed ? ReturnStatus.COMPLETED.name() : ReturnStatus.PARTIAL_EXECUTED.name());
        int rows = returnOrderMapper.update(order, new LambdaQueryWrapper<ReturnOrder>()
                .eq(ReturnOrder::getId, order.getId())
                .eq(ReturnOrder::getVersion, order.getVersion()));
        if (rows == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "退货单已被其他操作更新，请刷新后重试");
        }
        // 6. 批量更新退货单明细
        if (!returnOrderItemService.updateBatchById(returnItems.values())) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "退货明细更新失败，请刷新后重试");
        }
    }

    /**
     * 查询某个来源订单下，其他有效退货单对各来源明细的占用数量。
     * <p>草稿和已取消单不占用；已提交状态按申请数量占用，已审核及后续状态按审核数量占用
     * （审核通过后实际占用来源的量是审核量而非申请量，避免申请量大于审核量时虚占来源可退量）。
     * 编辑当前退货单时传 excludeReturnOrderId 排除自身。</p>
     */
    private Map<Long, Integer> buildOccupiedMap(ReturnType returnType, Long sourceOrderId, Long excludeReturnOrderId) {
        // 1. 查询所有有效退货单(排除自身, 草稿和已取消单)
        List<ReturnOrder> validOrders = returnOrderMapper.selectList(new LambdaQueryWrapper<ReturnOrder>()
                        .eq(ReturnOrder::getReturnType, returnType.name())
                        .eq(ReturnOrder::getSourceOrderId, sourceOrderId)
                        .notIn(ReturnOrder::getStatus, ReturnStatus.DRAFT.name(), ReturnStatus.CANCELLED.name())
                        .ne(excludeReturnOrderId != null, ReturnOrder::getId, excludeReturnOrderId));
        if (validOrders.isEmpty()) return Map.of();
        // 2. 构建退货单状态映射表(退货单ID -> 状态)
        Map<Long, ReturnStatus> orderStatusMap = validOrders.stream()
                .collect(Collectors.toMap(ReturnOrder::getId, o -> ReturnStatus.valueOf(o.getStatus())));
        List<Long> validOrderIds = validOrders.stream().map(ReturnOrder::getId).toList();
        return returnOrderItemMapper.selectList(new LambdaQueryWrapper<ReturnOrderItem>()
                        .in(ReturnOrderItem::getReturnOrderId, validOrderIds))
                .stream()
                .collect(Collectors.groupingBy(
                        ReturnOrderItem::getSourceOrderItemId,
                        Collectors.summingInt(item -> {
                            // 3. 计算占用数量（已提交按申请量，已审核按审核量）
                            ReturnStatus status = orderStatusMap.get(item.getReturnOrderId());
                            if (status == ReturnStatus.SUBMITTED) {
                                return item.getRequestedQty() == null ? 0 : item.getRequestedQty();
                            }
                            return item.getApprovedQty() == null ? 0 : item.getApprovedQty();
                        })));
    }

    /**
     * 批量查询多个来源订单下，有效退货单对各来源明细的占用数量。
     */
    private Map<Long, Map<Long, Integer>> buildOccupiedMapBatch(ReturnType returnType, List<Long> sourceOrderIds) {
        if (sourceOrderIds == null || sourceOrderIds.isEmpty()) return Map.of();
        // 1. 查询所有有效退货单(排除自身, 草稿和已取消单)
        List<ReturnOrder> validOrders = returnOrderMapper.selectList(new LambdaQueryWrapper<ReturnOrder>()
                .eq(ReturnOrder::getReturnType, returnType.name())
                .in(ReturnOrder::getSourceOrderId, sourceOrderIds)
                .notIn(ReturnOrder::getStatus, ReturnStatus.DRAFT.name(), ReturnStatus.CANCELLED.name()));
        if (validOrders.isEmpty()) return Map.of();
        // 2. 构建退货单状态映射表(退货单ID -> 状态)
        Map<Long, ReturnStatus> orderStatusMap = validOrders.stream()
                .collect(Collectors.toMap(ReturnOrder::getId, o -> ReturnStatus.valueOf(o.getStatus())));
        // 3. 构建退货单来源订单映射表(退货单ID -> 来源订单ID)
        Map<Long, Long> orderToSourceMap = validOrders.stream()
                .collect(Collectors.toMap(ReturnOrder::getId, ReturnOrder::getSourceOrderId));
        // 4. 查询所有有效退货单明细
        List<Long> validOrderIds = validOrders.stream().map(ReturnOrder::getId).toList();
        List<ReturnOrderItem> allItems = returnOrderItemMapper.selectList(new LambdaQueryWrapper<ReturnOrderItem>()
                .in(ReturnOrderItem::getReturnOrderId, validOrderIds));
        // 5. 构建占用数量映射表(来源订单ID -> 来源明细ID -> 占用数量)
        return allItems.stream()
                .collect(Collectors.groupingBy(
                        item -> orderToSourceMap.get(item.getReturnOrderId()),
                        Collectors.groupingBy(
                                ReturnOrderItem::getSourceOrderItemId,
                                Collectors.summingInt(item -> {
                                    ReturnStatus status = orderStatusMap.get(item.getReturnOrderId());
                                    if (status == ReturnStatus.SUBMITTED) {
                                        return item.getRequestedQty() == null ? 0 : item.getRequestedQty();
                                    }
                                    return item.getApprovedQty() == null ? 0 : item.getApprovedQty();
                                }))));
    }

    /** 将退货单主表转换为主表返回VO。 */
    private ReturnOrderVo toVo(ReturnOrder entity) {
        ReturnOrderVo vo = new ReturnOrderVo();
        BeanUtil.copyProperties(entity, vo, "id");
        vo.setReturnOrderId(entity.getId());
        vo.setTotalAmount(entity.getTotalAmount() == null ? null : QtyUtil.toDecimal(entity.getTotalAmount().longValue()));
        return vo;
    }

    /** 将退货单明细转换为详情明细，不在查看详情时额外聚合剩余可退数量。 */
    private ReturnOrderItemVo toItemVo(ReturnOrderItem item) {
        ReturnOrderItemVo vo = new ReturnOrderItemVo();
        BeanUtil.copyProperties(item, vo, "id");
        vo.setReturnOrderItemId(item.getId());
        vo.setSourceFulfilledQty(toDecimal(item.getSourceFulfilledQty()));
        vo.setRequestedQty(toDecimal(item.getRequestedQty()));
        vo.setApprovedQty(toDecimal(item.getApprovedQty()));
        vo.setProcessedQty(toDecimal(item.getProcessedQty()));
        vo.setUnitPrice(toDecimal(item.getUnitPrice()));
        vo.setTotalAmount(toDecimal(item.getTotalAmount()));
        return vo;
    }

    /** 确保每个退货方向有且仅有一个来源 Provider。 */
    private ReturnSourceProvider requireProvider(ReturnType returnType) {
        List<ReturnSourceProvider> matches = sourceProviders.stream().filter(provider -> provider.supportsType() == returnType).toList();
        if (matches.size() != 1) {
            String action = matches.isEmpty() ? "未部署" : "重复部署";
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), returnType + " 的来源业务能力" + action + "，暂不能处理退货单");
        }
        return matches.getFirst();
    }

    /** 校验退货方向为必填参数。 */
    private ReturnType requireType(ReturnType type) {
        if (type == null) throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "退货类型不能为空");
        return type;
    }

    /** 将数据库字符串转换为枚举，并统一抛出业务异常。 */
    private ReturnType parseType(String value) {
        try {
            return ReturnType.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "退货单类型无效");
        }
    }

    /** 将放大整数数量或金额转换为接口使用的十进制数值。 */
    private BigDecimal toDecimal(Integer value) {
        return value == null ? null : QtyUtil.toDecimal(value.longValue());
    }

    /**
     * 计算最终可退数量。
     *
     * <p>采购退货是出库操作，不能超过仓库可用库存；销售退货是入库操作，不受库存限制，
     * 只看来源已履约数量减去其他退货单占用。</p>
     */
    private int calculateAvailable(ReturnType returnType, int sourceAvailable, long stockAvail) {
        if (returnType == ReturnType.SALES_RETURN) {
            return Math.max(0, sourceAvailable);
        }
        return (int) Math.max(0L, Math.min(sourceAvailable, stockAvail));
    }

    /**
     * 批量查询指定仓库中一批产品的可用库存（可用 = stockQty - lockedQty，×100 整数）。
     *
     * <p>销售退货是入库操作，不消耗仓库库存，直接返回空 Map 跳过查询。</p>
     */
    private Map<Long, Long> loadStockAvailable(ReturnType returnType, Long warehouseId, List<Long> productIds) {
        if (returnType == ReturnType.SALES_RETURN) {
            return Map.of();
        }
        if (warehouseId == null || productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        List<WarehouseStock> stocks = warehouseStockMapper.selectList(new LambdaQueryWrapper<WarehouseStock>()
                .eq(WarehouseStock::getWarehouseId, warehouseId)
                .in(WarehouseStock::getProductId, productIds));
        return stocks.stream().collect(Collectors.toMap(
                WarehouseStock::getProductId,
                s -> (s.getStockQty() == null ? 0L : s.getStockQty())
                        - (s.getLockedQty() == null ? 0L : s.getLockedQty())
        ));
    }

    /** 查询退货单并校验状态与乐观锁版本。 */
    private ReturnOrder loadAndCheckStatus(Long returnOrderId, Integer version,
                                           ReturnStatus... allowedStatuses) {
        // 1. 校验退货单是否存在
        ReturnOrder order = returnOrderMapper.selectById(returnOrderId);
        if (order == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "退货单不存在");
        }
        // 2. 校验退货单状态是否允许编辑
        String status = order.getStatus();
        boolean allowed = false;
        for (ReturnStatus s : allowedStatuses) {
            if (s.name().equals(status)) { allowed = true; break; }
        }
        if (!allowed) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "当前状态不允许编辑");
        }
        // 3. 校验乐观锁版本是否正确
        if (version != null && !order.getVersion().equals(version)) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "数据已被他人修改，请刷新后重试");
        }
        return order;
    }

    /** 来源上下文：来源订单快照 + 来源明细映射 + 库存可用映射。 */
    private record SourceContext(ReturnSourceOrder sourceOrder,
                                 Map<Long, ReturnSourceItem> sourceItemMap,
                                 Map<Long, Long> stockAvailableMap) {}

    /** 一次性获取来源订单快照、来源明细映射、库存可用映射。 */
    private SourceContext resolveSourceContext(ReturnType returnType, Long sourceOrderId) {
        // 1. 查询来源订单快照
        ReturnSourceProvider provider = requireProvider(returnType);
        ReturnSourceOrder sourceOrder = provider.getSourceOrder(sourceOrderId);
        // 2. 查询来源订单明细快照
        List<ReturnSourceItem> sourceItems = provider.listSourceItems(List.of(sourceOrderId))
                .getOrDefault(sourceOrderId, List.of());
        // 3. 构建来源明细映射(来源订单明细ID -> 来源明细)
        Map<Long, ReturnSourceItem> sourceItemMap = sourceItems.stream()
                .collect(Collectors.toMap(ReturnSourceItem::sourceOrderItemId, item -> item));
        // 4. 查询库存可用数量
        List<Long> productIds = sourceItems.stream().map(ReturnSourceItem::productId).toList();
        // 5. 构建库存可用映射(产品ID -> 库存可用数量)
        Map<Long, Long> stockAvailableMap = loadStockAvailable(returnType, sourceOrder.warehouseId(), productIds);
        return new SourceContext(sourceOrder, sourceItemMap, stockAvailableMap);
    }

    /** 构建明细的输出结果：明细列表 + 总金额。 */
    private record BuiltItems(List<ReturnOrderItem> items, BigDecimal totalAmount) {}

    /**
     * 构建退货明细实体并校验
     * <p>一趟遍历完成：来源明细去重 → 查找来源 → 精度校验 → 两步可退数量校验
     */
    private BuiltItems buildReturnItems(ReturnType returnType,
                                         Map<Long, ReturnSourceItem> sourceItemMap,
                                         List<ReturnOrderItemCreateDto> dtoItems,
                                         Map<Long, Integer> occupied,
                                         Map<Long, Long> stockAvailableMap,
                                         Long returnOrderId) {
        // 1. 去重(来源订单明细ID)
        Set<Long> seen = new HashSet<>();
        // 2. 初始化总金额
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<ReturnOrderItem> items = new ArrayList<>();
        for (ReturnOrderItemCreateDto itemDto : dtoItems) {
            // 1. 校验来源订单明细ID是否存在且不重复
            Long sourceItemItemId = IdUtil.parseRequiredLongId(itemDto.getSourceOrderItemId(), "来源订单明细ID");
            if (!seen.add(sourceItemItemId)) {
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "退货明细重复，sourceOrderItemId=" + sourceItemItemId);
            }
            // 2. 查找来源明细
            ReturnSourceItem source = sourceItemMap.get(sourceItemItemId);
            if (source == null) {
                throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "来源明细不存在: " + sourceItemItemId);
            }
            // 3. 获取来源明细已入库数量
            int fulfilled = source.fulfilledQty() == null ? 0 : source.fulfilledQty();
            // 4. 计算已占用数量(已退回数量)
            int used = occupied.getOrDefault(sourceItemItemId, 0);
            // 5. 计算可退数量(已入库数量 - 已退回数量)
            int sourceAvailable = Math.max(0, fulfilled - used);
            // 6. 获取库存可用数量(产品ID -> 库存可用数量)
            long stockAvail = stockAvailableMap.getOrDefault(source.productId(), 0L);
            // 7. 计算最终可退数量（采购退货受库存限制，销售退货不受限）
            int available = calculateAvailable(returnType, sourceAvailable, stockAvail);
            int requestedStored = QtyUtil.toStored(itemDto.getRequestedQty()).intValue();
            // 8. 校验申请退回数量是否>0
            if (requestedStored <= 0) {
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "申请退回数量必须大于0");
            }
            // 9. 校验申请退回数量是否超过剩余可退数量
            if (requestedStored > available) {
                String detail = returnType == ReturnType.PURCHASE_RETURN
                        ? String.format("来源可退 %s，仓库可用库存 %s", toDecimal(sourceAvailable), toDecimal((int) Math.max(0L, stockAvail)))
                        : String.format("来源可退 %s", toDecimal(sourceAvailable));
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(),
                        "申请退回数量 " + toDecimal(requestedStored) + " 超过剩余可退数量 " + toDecimal(available) + "（" + detail + "）");
            }
            // 10. 校验申请退回数量是否符合精度要求
            int precision = source.quantityPrecision() == null ? 0 : source.quantityPrecision();
            validateQuantityPrecision(itemDto.getRequestedQty(), precision);
            // 11. 计算退货金额(申请退回数量 * 单价)
            int unitPriceStored = source.unitPrice() == null ? 0 : source.unitPrice();
            BigDecimal lineAmount = itemDto.getRequestedQty()
                    .multiply(BigDecimal.valueOf(unitPriceStored).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
            // 12. 累加退货金额
            totalAmount = totalAmount.add(lineAmount);
            ReturnOrderItem item = new ReturnOrderItem()
                    .setSourceOrderItemId(sourceItemItemId)
                    .setProductId(source.productId())
                    .setProductCode(source.productCode())
                    .setProductName(source.productName())
                    .setUnitName(source.unitName())
                    .setQuantityPrecision(precision)
                    .setSourceFulfilledQty(fulfilled)
                    .setRequestedQty(requestedStored)
                    .setApprovedQty(0)
                    .setProcessedQty(0)
                    .setUnitPrice(unitPriceStored)
                    .setTotalAmount(QtyUtil.toStored(lineAmount).intValue())
                    .setRemark(itemDto.getRemark());
            if (returnOrderId != null) {
                // 13. 设置退货单ID
                item.setReturnOrderId(returnOrderId);
            }
            items.add(item);
        }
        return new BuiltItems(items, totalAmount);
    }
}