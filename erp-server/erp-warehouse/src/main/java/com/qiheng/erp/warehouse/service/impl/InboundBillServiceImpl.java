package com.qiheng.erp.warehouse.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.product.mapper.ProductMapper;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.warehouse.domain.dto.InboundBillCreateDto;
import com.qiheng.erp.warehouse.domain.dto.InboundBillItemCreateDto;
import com.qiheng.erp.warehouse.domain.dto.InboundBillPageDto;
import com.qiheng.erp.warehouse.domain.entity.InboundBill;
import com.qiheng.erp.warehouse.domain.entity.InboundBillItem;
import com.qiheng.erp.warehouse.domain.entity.Warehouse;
import com.qiheng.erp.warehouse.domain.entity.WarehouseStock;
import com.qiheng.erp.warehouse.domain.enums.EntryMode;
import com.qiheng.erp.warehouse.domain.enums.InboundBillStatus;
import com.qiheng.erp.warehouse.domain.enums.InboundType;
import com.qiheng.erp.warehouse.domain.enums.SourceType;

import com.qiheng.erp.warehouse.domain.vo.InboundBillDetailVo;
import com.qiheng.erp.warehouse.domain.vo.InboundBillListItemVo;
import com.qiheng.erp.warehouse.domain.vo.InboundBillPageVo;
import com.qiheng.erp.warehouse.domain.vo.InboundBillSummaryVo;
import com.qiheng.erp.warehouse.mapper.InboundBillMapper;
import com.qiheng.erp.warehouse.service.IInboundBillItemService;
import com.qiheng.erp.warehouse.service.IInboundBillService;
import com.qiheng.erp.warehouse.service.IWarehouseService;
import com.qiheng.erp.warehouse.service.IWarehouseStockService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private IWarehouseService warehouseService;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

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
                populateQuantityFields(vo, items);
            }
        }
        
        // 基于当前分页记录计算汇总信息
        InboundBillSummaryVo summary = computeSummary(records);

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
     * 填充入库单数量字段    
     * @param vo 入库单列表项VO
     * @param items 入库单明细列表VO
     */
    private void populateQuantityFields(InboundBillListItemVo vo, List<InboundBillItem> items) {
        if (items == null || items.isEmpty()) {
            vo.setItemCount(0);
            vo.setTotalCurrentQty(0);
            vo.setQuantityUnitName("");
            vo.setQuantitySummary("0");
            return;
        }
        int itemCount = items.size();
        vo.setItemCount(itemCount);
        // 计算总数量
        long sumRaw = items.stream()
                .mapToLong(item -> item.getCurrentQty() != null ? item.getCurrentQty() : 0L)
                .sum();
        long total = sumRaw / 100L;
        vo.setTotalCurrentQty((int) total);

        if (itemCount >= 2) {
            // 两个及以上商品，单位字段显示"n个商品"
            vo.setQuantityUnitName(itemCount + "个商品");
        } else {
            // 1个商品，显示明细单位
            String unitName = items.getFirst().getUnitName();
            vo.setQuantityUnitName(unitName != null ? unitName : "");
        }

        // 构建数量摘要："本次入库 : 商品名称+数量, 商品名称+数量"
        StringBuilder sb = new StringBuilder("本次入库 : ");
        for (int i = 0; i < itemCount; i++) {
            // 处理每个商品的摘要
            InboundBillItem item = items.get(i);
            long qtyRaw = item.getCurrentQty() != null ? item.getCurrentQty() : 0L;
            long qty = qtyRaw / 100L;
            String productName = item.getProductName() != null ? item.getProductName() : "";
            String unitName = item.getUnitName() != null ? item.getUnitName() : "";
            sb.append(productName).append(qty);
            if (StrUtil.isNotBlank(unitName)) {
                sb.append(unitName);
            }
            if (i < itemCount - 1) {
                sb.append(" , ");
            }
        }
        vo.setQuantitySummary(sb.toString());
    }
    
    /**
     * 基于当前分页记录计算入库单汇总
     * @param records 当前分页的入库单记录
     * @return 入库单汇总信息
     */
    private InboundBillSummaryVo computeSummary(List<InboundBillListItemVo> records) {
        InboundBillSummaryVo summary = new InboundBillSummaryVo();
        int sourceGeneratedCount = 0;
        int pendingCount = 0;
        int confirmedCount = 0;
        int cancelledCount = 0;

        for (InboundBillListItemVo vo : records) {
            // 统计系统自动录单数
            if (EntryMode.SOURCE_GENERATED.name().equals(vo.getEntryMode())) {
                sourceGeneratedCount++;
            }
            // 按状态统计
            String status = vo.getStatus();
            if (InboundBillStatus.PENDING_CONFIRM.name().equals(status)) {
                pendingCount++;
            } else if (InboundBillStatus.CONFIRMED.name().equals(status)) {
                confirmedCount++;
            } else if (InboundBillStatus.CANCELLED.name().equals(status)) {
                cancelledCount++;
            }
        }

        summary.setSourceGeneratedCount(sourceGeneratedCount);
        summary.setPendingCount(pendingCount);
        summary.setConfirmedCount(confirmedCount);
        summary.setCancelledCount(cancelledCount);
        return summary;
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
        // 查当前仓库这批产品的库存（一次SQL，避免N+1）
        Map<Long, Long> stockQtyByProductId = getItemDetails(bill, items);

        InboundBillDetailVo vo = convertToDetailVo(bill);
        // 复用填充数量字段的逻辑（itemCount、总数量、单位、摘要）
        populateQuantityFields(vo, items);
        // 转换明细项（根据订单状态推导 before/change/after_qty）
        vo.setItems(convertToDetailItemVos(items, stockQtyByProductId, bill.getStatus()));
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
        List<Product> products = productMapper.selectByIds(productIds);
        if (products.size() != productIds.size()) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "部分产品不存在");
        }
        Map<Long, Product> productMap = products.stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        // 3. 确定来源类型和录入方式
        InboundType billType = dto.getBillType();
        String sourceType = resolveSourceType(billType);
        String entryMode = resolveEntryMode(billType);

        // 4. 生成入库单号
        String inboundNo = generateInboundNo();

        // 5. 获取当前登录用户
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
                .setStatus(InboundBillStatus.DRAFT.name())
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

            InboundBillItem item = new InboundBillItem()
                    .setInboundBillId(bill.getId())
                    .setInboundNo(inboundNo)
                    .setSourceItemId(StrUtil.isNotBlank(itemDto.getSourceItemId()) ? Long.valueOf(itemDto.getSourceItemId()) : null)
                    .setProductId(productId)
                    .setProductCode(product.getProductCode())
                    .setProductName(product.getProductName())
                    .setUnitName(product.getUnitName())
                    .setQuantityPrecision(product.getQuantityPrecision())
                    .setPlanQty(QtyUtil.toStored(itemDto.getPlanQty()))
                    .setProcessedQty(null)
                    .setCurrentQty(QtyUtil.toStored(itemDto.getCurrentQty()))
                    .setPendingQty(null)
                    .setQualifiedQty(QtyUtil.toStored(itemDto.getQualifiedQty()))
                    .setDefectiveQty(QtyUtil.toStored(itemDto.getDefectiveQty()))
                    .setRemark(itemDto.getRemark());
            items.add(item);
        }
        inboundBillItemService.saveBatch(items);

        // 8. 查询当前库存，组装返回详情
        Map<Long, Long> stockQtyByProductId = getItemDetails(bill, items);

        // 9. 组装详情VO
        // 重新查询明细以获取数据库生成的ID和时间
        List<InboundBillItem> savedItems = inboundBillItemService.list(
                new LambdaQueryWrapper<InboundBillItem>()
                        .eq(InboundBillItem::getInboundBillId, bill.getId())
                        .orderByAsc(InboundBillItem::getId)
        );
        InboundBillDetailVo vo = convertToDetailVo(bill);
        populateQuantityFields(vo, savedItems);
        vo.setItems(convertToDetailItemVos(savedItems, stockQtyByProductId, bill.getStatus()));
        return vo;
    }

    /**
     * 根据入库单ID查询入库单明细列表并生成库存数量映射表
     * @param bill 入库单实体
     * @param items 入库单明细列表
     * @return 产品ID到库存数量的映射
     */
    private Map<Long, Long> getItemDetails(InboundBill bill, List<InboundBillItem> items) {
        List<Long> distinctProductIds = items.stream()
                .map(InboundBillItem::getProductId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Long> stockQtyByProductId;
        if (distinctProductIds.isEmpty() || bill.getWarehouseId() == null) {
            stockQtyByProductId = Collections.emptyMap();
        } else {
            List<WarehouseStock> stocks = warehouseStockService.list(
                    new LambdaQueryWrapper<WarehouseStock>()
                            .eq(WarehouseStock::getWarehouseId, bill.getWarehouseId())
                            .in(WarehouseStock::getProductId, distinctProductIds)
            );
            stockQtyByProductId = stocks.stream().collect(Collectors.toMap(
                    WarehouseStock::getProductId,
                    s -> s.getStockQty() != null ? s.getStockQty() : 0L,
                    (a, b) -> a
            ));
        }
        return stockQtyByProductId;
    }

    /**
     * 入库单主表转详情VO
     * @param bill 入库单实体
     * @return 详情VO
     */
    private InboundBillDetailVo convertToDetailVo(InboundBill bill) {
        InboundBillDetailVo vo = new InboundBillDetailVo();
        vo.setWorkBillId(String.valueOf(bill.getId()));
        vo.setBillNo(bill.getInboundNo());
        vo.setSourceType(bill.getSourceType());
        vo.setSourceId(bill.getSourceId() != null ? String.valueOf(bill.getSourceId()) : null);
        vo.setSourceNo(bill.getSourceNo());
        vo.setSourcePartyId(bill.getSourcePartyId() != null ? String.valueOf(bill.getSourcePartyId()) : null);
        vo.setSourcePartyName(bill.getSourcePartyName());
        vo.setEntryMode(bill.getEntryMode());
        vo.setWarehouseId(bill.getWarehouseId() != null ? String.valueOf(bill.getWarehouseId()) : null);
        vo.setWarehouseName(bill.getWarehouseName());
        vo.setStatus(bill.getStatus());
        vo.setConfirmedById(bill.getConfirmedById() != null ? String.valueOf(bill.getConfirmedById()) : null);
        vo.setConfirmedByName(bill.getConfirmedByName());
        vo.setConfirmedAt(bill.getConfirmedAt());
        vo.setCreatedById(bill.getCreatedById() != null ? String.valueOf(bill.getCreatedById()) : null);
        vo.setCreatedByName(bill.getCreatedByName());
        vo.setResponsibleById(bill.getResponsibleById() != null ? String.valueOf(bill.getResponsibleById()) : null);
        vo.setResponsibleByName(bill.getResponsibleByName());
        vo.setVersion(bill.getVersion());
        vo.setCreateTime(bill.getCreateTime());
        vo.setUpdateTime(bill.getUpdateTime());
        vo.setBillType(bill.getInboundType());
        vo.setManualReason(bill.getManualReason());
        vo.setRemark(bill.getRemark());
        return vo;
    }

    /**
     * 入库单明细转详情明细VO（按状态推导 before/change/after_qty）
     * @param items 入库单明细实体列表
     * @param stockQtyByProductId 当前仓库按productId映射的库存数量（原始100倍整数）
     * @param billStatus 入库单状态（DRAFT / PENDING_CONFIRM / CONFIRMED / CANCELLED）
     * @return 详情明细VO列表
     */
    private List<InboundBillDetailVo.InboundBillDetailItemVo> convertToDetailItemVos(
            List<InboundBillItem> items,
            Map<Long, Long> stockQtyByProductId,
            String billStatus) {
        boolean isConfirmed = InboundBillStatus.CONFIRMED.name().equals(billStatus);
        return items.stream().map(item -> {
            InboundBillDetailVo.InboundBillDetailItemVo ivo = new InboundBillDetailVo.InboundBillDetailItemVo();
            // 基本字段
            ivo.setWorkBillItemId(String.valueOf(item.getId()));
            ivo.setWorkBillId(String.valueOf(item.getInboundBillId()));
            ivo.setBillNo(item.getInboundNo());
            ivo.setSourceItemId(item.getSourceItemId() != null ? String.valueOf(item.getSourceItemId()) : null);
            ivo.setProductId(String.valueOf(item.getProductId()));
            ivo.setProductCode(item.getProductCode());
            ivo.setProductName(item.getProductName());
            ivo.setUnitName(item.getUnitName());
            ivo.setQuantityPrecision(item.getQuantityPrecision());

            // 数量全部除以 100（数据库按 100 倍整数存储），契约为 number,null
            ivo.setPlanQty(QtyUtil.toDecimal(item.getPlanQty()));
            ivo.setProcessedQty(QtyUtil.toDecimal(item.getProcessedQty()));
            ivo.setPendingQty(QtyUtil.toDecimal(item.getPendingQty()));
            BigDecimal currentQty = QtyUtil.toDecimal(item.getCurrentQty());
            // current / qualified / defective / before / change / after 契约为 number（不允许null），默认0
            ivo.setCurrentQty(currentQty != null ? currentQty : BigDecimal.ZERO);
            ivo.setQualifiedQty(QtyUtil.defaultZero(QtyUtil.toDecimal(item.getQualifiedQty())));
            ivo.setDefectiveQty(QtyUtil.defaultZero(QtyUtil.toDecimal(item.getDefectiveQty())));

            // 根据状态推导前后数量（O(1)从Map取，BigDecimal计算保证精度）
            Long rawStock = stockQtyByProductId.get(item.getProductId());
            BigDecimal stock = QtyUtil.defaultZero(QtyUtil.toDecimal(rawStock));
            BigDecimal cq = QtyUtil.defaultZero(currentQty);
            BigDecimal beforeQty;
            BigDecimal afterQty;
            if (isConfirmed) {
                afterQty = stock;
                beforeQty = stock.subtract(cq);
                // 避免负数
                if (beforeQty.compareTo(BigDecimal.ZERO) < 0) {
                    beforeQty = BigDecimal.ZERO;
                }
            } else {
                beforeQty = stock;
                afterQty = stock.add(cq);
            }
            ivo.setBeforeQty(beforeQty);
            ivo.setChangeQty(QtyUtil.defaultZero(currentQty));
            ivo.setAfterQty(afterQty);

            ivo.setStockBillItemId(item.getStockBillItemId() != null ? String.valueOf(item.getStockBillItemId()) : null);
            ivo.setCreateTime(item.getCreateTime());
            ivo.setUpdateTime(item.getUpdateTime());
            ivo.setRemark(item.getRemark());
            return ivo;
        }).collect(Collectors.toList());
    }


    private static final DateTimeFormatter INBOUND_NO_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 通过Redis自增生成入库单号，格式：IByyyyMMddNNNN
     * @return 入库单号
     */
    private String generateInboundNo() {
        String dateStr = LocalDate.now().format(INBOUND_NO_DATE_FMT);
        String redisKey = "inbound:bill:no:" + dateStr;
        Long seq = stringRedisTemplate.opsForValue().increment(redisKey);
        if (seq == null) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "生成入库单号失败");
        }
        return "IB" + dateStr + String.format("%04d", seq);
    }

    /**
     * 根据入库类型确定来源类型
     */
    private static String resolveSourceType(InboundType billType) {
        return switch (billType) {
            case PURCHASE_IN -> SourceType.PURCHASE_ORDER.name();
            case SALES_RETURN -> SourceType.SALES_RETURN_ORDER.name();
            case ADJUST_IN -> SourceType.STOCK_ADJUST.name();
        };
    }

    /**
     * 根据入库类型确定录入方式
     */
    private static String resolveEntryMode(InboundType billType) {
        return switch (billType) {
            case PURCHASE_IN, SALES_RETURN -> EntryMode.MANUAL_SUPPLEMENT.name();
            case ADJUST_IN -> EntryMode.MANUAL_ADJUSTMENT.name();
        };
    }
}