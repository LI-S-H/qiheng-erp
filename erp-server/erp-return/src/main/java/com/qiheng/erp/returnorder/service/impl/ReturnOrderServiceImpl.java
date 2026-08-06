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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        // 1. 拿来源订单的仓库ID（库存是按仓库维度存的）
        ReturnSourceOrder sourceOrder = provider.getSourceOrder(sourceOrderId);
        Long warehouseId = sourceOrder.warehouseId();
        // 2. 查询已被其他有效退货单占用的数量（排除草稿和取消）
        Map<Long, Integer> occupied = buildOccupiedMap(returnType, sourceOrderId, null);
        // 3. 来源单明细（含入库数量）
        List<ReturnSourceItem> sourceItems = provider.listSourceItems(sourceOrderId);
        if (sourceItems.isEmpty()) return List.of();
        // 4. 批量查这些产品在该仓库的可用库存（可用 = stockQty - lockedQty，放大100倍整数）
        List<Long> productIds = sourceItems.stream().map(ReturnSourceItem::productId).toList();
        Map<Long, Long> stockAvailableMap = Map.of();
        if (warehouseId != null && !productIds.isEmpty()) {
            // 批量查询库存
            List<WarehouseStock> stocks = warehouseStockMapper.selectList(new LambdaQueryWrapper<WarehouseStock>()
                    .eq(WarehouseStock::getWarehouseId, warehouseId)
                    .in(WarehouseStock::getProductId, productIds));
            // 可用库存Map （可用 = stockQty - lockedQty，放大100倍整数）
            stockAvailableMap = stocks.stream().collect(Collectors.toMap(
                    WarehouseStock::getProductId,
                    s -> (s.getStockQty() == null ? 0L : s.getStockQty())
                            - (s.getLockedQty() == null ? 0L : s.getLockedQty())
            ));
        }
        // 5. 转换时取「入库数 vs 可用库存」较小值再减占用
        Map<Long, Long> finalStockAvailableMap = stockAvailableMap;
        return sourceItems.stream().map(source -> {
            // 来源单对应明细的已入库数量
            int fulfilled = source.fulfilledQty() == null ? 0 : source.fulfilledQty();
            // 该产品在该仓库的可用库存
            long stockAvail = finalStockAvailableMap.getOrDefault(source.productId(), 0L);
            // 可退数量（取「入库数 vs 可用库存」较小值）
            int cap = (int) Math.max(0L, Math.min(fulfilled, stockAvail));
            // 已被其他有效退货单占用的数量（排除草稿和取消）
            int used = occupied.getOrDefault(source.sourceOrderItemId(), 0);
            ReturnOrderItemVo vo = new ReturnOrderItemVo();
            vo.setSourceOrderItemId(source.sourceOrderItemId());
            vo.setProductId(source.productId());
            vo.setProductCode(source.productCode());
            vo.setProductName(source.productName());
            vo.setUnitName(source.unitName());
            vo.setQuantityPrecision(source.quantityPrecision());
            vo.setSourceFulfilledQty(toDecimal(fulfilled));
            vo.setOccupiedQty(toDecimal(used));
            vo.setUnitPrice(toDecimal(source.unitPrice()));
            vo.setAvailableReturnQty(toDecimal(Math.max(0, cap - used)));
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
        ReturnType returnType = requireType(dto.getReturnType());
        ReturnSourceProvider provider = requireProvider(returnType);
        Long sourceOrderId = IdUtil.parseRequiredLongId(dto.getSourceOrderId(), "来源订单ID");
        // 1. 获取来源订单快照
        ReturnSourceOrder sourceOrder = provider.getSourceOrder(sourceOrderId);
        // 2. 校验前端传回来的退货仓库ID与实际入库仓库的ID是否一致
        Long warehouseId = IdUtil.parseRequiredLongId(dto.getWarehouseId(), "退货仓库ID");
        if (!warehouseId.equals(sourceOrder.warehouseId())) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "退货仓库必须与原订单仓库一致");
        }
        // 3. 获取来源明细并建立映射
        List<ReturnSourceItem> sourceItems = provider.listSourceItems(sourceOrderId);
        Map<Long, ReturnSourceItem> sourceItemMap = sourceItems.stream()
                .collect(Collectors.toMap(ReturnSourceItem::sourceOrderItemId, item -> item));
        // 4. 校验前端传回来的退货明细ID唯一性
        Set<Long> sourceItemIds = new HashSet<>();
        for (ReturnOrderItemCreateDto itemDto : dto.getItems()) {
            Long sourceItemItemId = IdUtil.parseRequiredLongId(itemDto.getSourceOrderItemId(), "来源订单明细ID");
            if (!sourceItemIds.add(sourceItemItemId)) {
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "退货明细重复，sourceOrderItemId=" + sourceItemItemId);
            }
        }
        // 5. 计算占用数量
        Map<Long, Integer> occupied = buildOccupiedMap(returnType, sourceOrderId, null);
        // 6. 构建明细实体并累加总金额
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<ReturnOrderItem> items = new ArrayList<>();
        for (ReturnOrderItemCreateDto itemDto : dto.getItems()) {
            Long sourceItemItemId = IdUtil.parseRequiredLongId(itemDto.getSourceOrderItemId(), "来源订单明细ID");
            ReturnSourceItem source = sourceItemMap.get(sourceItemItemId);
            if (source == null) {
                throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "来源明细不存在: " + sourceItemItemId);
            }
            // 来源单对应明细的已入库数量
            int fulfilled = source.fulfilledQty() == null ? 0 : source.fulfilledQty();
            // 已被其他有效退货单占用的数量（排除草稿和取消）
            int used = occupied.getOrDefault(sourceItemItemId, 0);
            // 可退数量（取「入库数 vs 可用库存」较小值）
            int available = Math.max(0, fulfilled - used);
            int requestedStored = QtyUtil.toStored(itemDto.getRequestedQty()).intValue();
            if (requestedStored <= 0) {
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "申请退回数量必须大于0");
            }
            if (requestedStored > available) {
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "申请退回数量超过剩余可退数量");
            }
            // 校验小数位
            int precision = source.quantityPrecision() == null ? 0 : source.quantityPrecision();
            validateQuantityPrecision(itemDto.getRequestedQty(), precision);
            // 计算明细金额（unitPrice 已是 ×100 存储值）
            int unitPriceStored = source.unitPrice() == null ? 0 : source.unitPrice();
            BigDecimal lineAmount = itemDto.getRequestedQty()
                    .multiply(BigDecimal.valueOf(unitPriceStored)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
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
            items.add(item);
        }
        // 7. 构建主表
        LoginUser loginUser = UserContext.requireCurrentUser();
        String returnNo = billNoGenerator.nextNo(returnType == ReturnType.PURCHASE_RETURN ? "PR" : "SR");
        ReturnOrder order = new ReturnOrder()
                .setReturnNo(returnNo)
                .setReturnType(returnType.name())
                .setSourceOrderId(sourceOrderId)
                .setSourceOrderNo(sourceOrder.sourceOrderNo())
                .setPartyId(sourceOrder.partyId())
                .setPartyCode(sourceOrder.partyCode())
                .setPartyName(sourceOrder.partyName())
                .setWarehouseId(warehouseId)
                .setWarehouseName(sourceOrder.warehouseName())
                .setExpectedExecutionDate(dto.getExpectedExecutionDate())
                .setHandlingType(dto.getHandlingType())
                .setReasonCode(dto.getReasonCode())
                .setReturnReason(dto.getReturnReason())
                .setTotalAmount(QtyUtil.toStored(totalAmount).intValue())
                .setStatus(ReturnStatus.DRAFT.name())
                .setStatusReason("")
                .setCreatedById(loginUser.getUserId())
                .setCreatedByName(loginUser.getRealName())
                .setRemark(dto.getRemark());
        returnOrderMapper.insert(order);
        // 8. 回填明细主键关联并批量插入
        items.forEach(item -> item.setReturnOrderId(order.getId()));
        returnOrderItemService.saveBatch(items);
        // 9. 返回详情
        return getDetail(order.getId());
    }

    /** 校验申请数量的小数位不超过产品精度。 */
    private void validateQuantityPrecision(BigDecimal qty, int precision) {
        if (qty == null) return;
        int scale = qty.stripTrailingZeros().scale();
        if (scale < 0) scale = 0;
        if (scale > precision) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "数量小数位不得超过" + precision + "位");
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
        // 获取退货单来源单的 Provider
        ReturnSourceProvider provider = requireProvider(returnType);
        return provider.searchSourceOrders(sourceOrderNo, limit).stream().map(source -> {
            List<ReturnOrderItemVo> items = listSourceItems(returnType, source.sourceOrderId());
            ReturnableSourceOrderVo vo = new ReturnableSourceOrderVo();
            vo.setSourceOrderId(source.sourceOrderId());
            vo.setSourceOrderNo(source.sourceOrderNo());
            vo.setPartyId(source.partyId());
            vo.setPartyCode(source.partyCode());
            vo.setPartyName(source.partyName());
            vo.setWarehouseId(source.warehouseId());
            vo.setWarehouseName(source.warehouseName());
            vo.setFulfilledItemCount((int) items.stream().filter(item -> item.getAvailableReturnQty() != null
                    && item.getAvailableReturnQty().signum() > 0).count());
            vo.setTotalAvailableReturnQty(items.stream().map(ReturnOrderItemVo::getAvailableReturnQty)
                    .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
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
        ReturnOrder order = returnOrderMapper.selectById(bill.getSourceId());
        if (order == null || parseType(order.getReturnType()) != ReturnType.PURCHASE_RETURN) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "采购退货出库单未关联有效退货单");
        }
        Map<Long, ReturnOrderItem> returnItems = returnOrderItemMapper.selectList(new LambdaQueryWrapper<ReturnOrderItem>()
                .eq(ReturnOrderItem::getReturnOrderId, order.getId())).stream()
                .collect(Collectors.toMap(ReturnOrderItem::getId, item -> item));
        for (OutboundBillItem outboundItem : items) {
            ReturnOrderItem returnItem = returnItems.get(outboundItem.getSourceItemId());
            if (returnItem == null || outboundItem.getCurrentQty() == null) {
                throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "采购退货出库明细未关联有效退货明细");
            }
            int approved = returnItem.getApprovedQty() == null ? 0 : returnItem.getApprovedQty();
            int processed = returnItem.getProcessedQty() == null ? 0 : returnItem.getProcessedQty();
            long nextProcessed = (long) processed + outboundItem.getCurrentQty();
            if (nextProcessed > approved) {
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "采购退货出库数量超过已审核数量");
            }
            returnItem.setProcessedQty((int) nextProcessed);
        }
        for (ReturnOrderItem returnItem : returnItems.values()) {
            if (returnOrderItemMapper.updateById(returnItem) != 1) {
                throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "退货明细已被其他操作更新，请刷新后重试");
            }
        }
        boolean completed = returnItems.values().stream().allMatch(item ->
                (item.getApprovedQty() == null ? 0 : item.getApprovedQty()) <= (item.getProcessedQty() == null ? 0 : item.getProcessedQty()));
        order.setStatus(completed ? ReturnStatus.COMPLETED.name() : ReturnStatus.PARTIAL_EXECUTED.name());
        if (returnOrderMapper.updateById(order) != 1) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "退货单已被其他操作更新，请刷新后重试");
        }
    }

    /**
     * 查询某个来源订单下，其他有效退货单对各来源明细的占用数量。
     *
     * <p>草稿和已取消单不占用；已提交及后续状态按申请数量占用。
     * 编辑当前退货单时传 excludeReturnOrderId 排除自身。</p>
     */
    private Map<Long, Integer> buildOccupiedMap(ReturnType returnType, Long sourceOrderId, Long excludeReturnOrderId) {
        // 1. 查同来源订单的有效退货单ID（非草稿、非取消，排除自身）
        List<Long> validOrderIds = returnOrderMapper.selectList(new LambdaQueryWrapper<ReturnOrder>()
                        .eq(ReturnOrder::getReturnType, returnType.name())
                        .eq(ReturnOrder::getSourceOrderId, sourceOrderId)
                        .notIn(ReturnOrder::getStatus, ReturnStatus.DRAFT.name(), ReturnStatus.CANCELLED.name())
                        .ne(excludeReturnOrderId != null, ReturnOrder::getId, excludeReturnOrderId))
                .stream().map(ReturnOrder::getId).toList();
        if (validOrderIds.isEmpty()) return Map.of();
        // 2. 查这些退货单的明细，按来源明细ID分组求和
        return returnOrderItemMapper.selectList(new LambdaQueryWrapper<ReturnOrderItem>()
                        .in(ReturnOrderItem::getReturnOrderId, validOrderIds))
                .stream()
                .collect(Collectors.groupingBy(
                        ReturnOrderItem::getSourceOrderItemId,
                        Collectors.summingInt(item -> item.getRequestedQty() == null ? 0 : item.getRequestedQty())));
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

    /** 将来源模块快照转换为创建退货单时使用的可退明细。 */
    private ReturnOrderItemVo toSourceItemVo(ReturnSourceItem item, Map<Long, Integer> occupied) {
        ReturnOrderItemVo vo = new ReturnOrderItemVo();
        vo.setSourceOrderItemId(item.sourceOrderItemId());
        vo.setProductId(item.productId());
        vo.setProductCode(item.productCode());
        vo.setProductName(item.productName());
        vo.setUnitName(item.unitName());
        vo.setQuantityPrecision(item.quantityPrecision());
        vo.setSourceFulfilledQty(toDecimal(item.fulfilledQty()));
        vo.setOccupiedQty(toDecimal(occupied.getOrDefault(item.sourceOrderItemId(), 0)));
        vo.setUnitPrice(toDecimal(item.unitPrice()));
        vo.setAvailableReturnQty(toDecimal(Math.max(0, (item.fulfilledQty() == null ? 0 : item.fulfilledQty())
                - occupied.getOrDefault(item.sourceOrderItemId(), 0))));
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
}
