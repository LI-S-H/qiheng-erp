package com.qiheng.erp.purchase.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.util.CodeNoDefinition;
import com.qiheng.erp.common.util.CodeNoGenerator;
import com.qiheng.erp.common.util.IdUtil;
import com.qiheng.erp.common.util.ParamValidator;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.domain.purchaseorder.enums.PurchaseOrderStatus;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierBatchDeleteDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierBatchStatusDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierCreateDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierPageDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierUpdateDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierServiceScoreDto;
import com.qiheng.erp.purchase.domain.supplier.entity.Supplier;
import com.qiheng.erp.purchase.domain.supplierproduct.enums.SupplierScoreStatus;
import com.qiheng.erp.purchase.domain.supplierproduct.entity.SupplierProduct;
import com.qiheng.erp.purchase.domain.supplier.vo.SupplierBatchFailure;
import com.qiheng.erp.purchase.domain.supplier.vo.SupplierVo;
import com.qiheng.erp.purchase.domain.supplier.vo.SupplierSummaryVo;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.purchase.mapper.SupplierMapper;
import com.qiheng.erp.purchase.mapper.SupplierProductMapper;
import com.qiheng.erp.purchase.service.ISupplierScoreChangeLogService;
import com.qiheng.erp.purchase.service.ISupplierService;
import com.qiheng.erp.returnorder.domain.entity.ReturnOrder;
import com.qiheng.erp.returnorder.domain.enums.ReturnStatus;
import com.qiheng.erp.returnorder.domain.port.ReturnType;
import com.qiheng.erp.returnorder.mapper.ReturnOrderMapper;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * <p>
 * 供应商表 服务实现类
 * </p>
 *
 * @author Li
 * @since 2026-07-29
 */
@Service
public class SupplierServiceImpl extends ServiceImpl<SupplierMapper, Supplier> implements ISupplierService {
    @Autowired
    private SupplierMapper supplierMapper;
    @Autowired
    private SupplierProductMapper supplierProductMapper;
    @Autowired
    private PurchaseOrderMapper purchaseOrderMapper;
    @Autowired
    private ReturnOrderMapper returnOrderMapper;
    @Autowired
    private CodeNoGenerator codeNoGenerator;
    @Autowired
    private ISupplierScoreChangeLogService supplierScoreChangeLogService;
    private static final CodeNoDefinition SUPPLIER_CODE = new CodeNoDefinition("supplier:code", "S", 4);
    /**
     * 供应商分页查询
     * @param dto 分页查询参数DTO
     * @return 分页查询结果VO
     */
    @Override
    public PageResult<SupplierVo> page(SupplierPageDto dto) {
        validatePageQuery(dto);
        LambdaQueryWrapper<Supplier> wrapper = buildFilter(dto)
                .orderByDesc(Supplier::getCreateTime)
                .orderByDesc(Supplier::getId);
        Page<Supplier> result = supplierMapper.selectPage(dto.toPage(), wrapper);
        return PageResult.of(
                result.getRecords().stream().map(this::toVo).toList(),
                (int) result.getTotal(),
                (int) result.getCurrent(),
                (int) result.getSize()
        );
    }

    /**
        * 供应商统计查询
      */
    @Override
    public SupplierSummaryVo summary(SupplierPageDto dto) {
        validatePageQuery(dto);
        SupplierSummaryVo summary = supplierMapper.selectSummary(dto);
        if (summary == null) {
            summary = new SupplierSummaryVo();
        }
        summary.setTotalCount(summary.getTotalCount() == null ? 0L : summary.getTotalCount());
        summary.setEnabledCount(summary.getEnabledCount() == null ? 0L : summary.getEnabledCount());
        summary.setReadyCount(summary.getReadyCount() == null ? 0L : summary.getReadyCount());
        summary.setAverageOverallScore(QtyUtil.toDecimal(summary.getAverageOverallScore()));
        return summary;
    }

    /**
        * 供应商详情查询
      */
    @Override
    public SupplierVo detail(Long supplierId) {
        Supplier supplier = supplierMapper.selectById(supplierId);
        if (supplier == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
        return toVo(supplier);
    }

    /**
        * 构建供应商分页查询条件
      */
    private LambdaQueryWrapper<Supplier> buildFilter(SupplierPageDto dto) {
        return new LambdaQueryWrapper<Supplier>()
                .like(StrUtil.isNotBlank(dto.getSupplierCode()), Supplier::getSupplierCode, dto.getSupplierCode())
                .like(StrUtil.isNotBlank(dto.getSupplierName()), Supplier::getSupplierName, dto.getSupplierName())
                .like(StrUtil.isNotBlank(dto.getContactName()), Supplier::getContactName, dto.getContactName())
                .eq(dto.getStatus() != null, Supplier::getStatus, dto.getStatus())
                .eq(StrUtil.isNotBlank(dto.getScoreStatus()), Supplier::getScoreStatus, dto.getScoreStatus())
                .ge(dto.getOverallScoreMin() != null, Supplier::getOverallScore, toStoredScore(dto.getOverallScoreMin()))
                .le(dto.getOverallScoreMax() != null, Supplier::getOverallScore, toStoredScore(dto.getOverallScoreMax()))
                .ge(dto.getServiceScoreMin() != null, Supplier::getServiceScore, toStoredScore(dto.getServiceScoreMin()))
                .le(dto.getServiceScoreMax() != null, Supplier::getServiceScore, toStoredScore(dto.getServiceScoreMax()))
                .ge(dto.getScoreBasisAmountMin() != null, Supplier::getScoreBasisAmount, QtyUtil.toStored(dto.getScoreBasisAmountMin()))
                .le(dto.getScoreBasisAmountMax() != null, Supplier::getScoreBasisAmount, QtyUtil.toStored(dto.getScoreBasisAmountMax()))
                .ge(dto.getAvgDeliveryDaysMin() != null, Supplier::getAvgDeliveryDays, dto.getAvgDeliveryDaysMin())
                .le(dto.getAvgDeliveryDaysMax() != null, Supplier::getAvgDeliveryDays, dto.getAvgDeliveryDaysMax());
    }

    /**
        * 验证分页查询参数
      */
    private void validatePageQuery(SupplierPageDto dto) {
        // 1. 验证评分状态
        ParamValidator.validateEnum(dto.getScoreStatus(), "评分状态",
                SupplierScoreStatus.NOT_READY.name(), SupplierScoreStatus.READY.name());
        // 2. 验证评分范围
        ParamValidator.validateScoreRange(dto.getOverallScoreMin(), dto.getOverallScoreMax(), "综合分");
        ParamValidator.validateScoreRange(dto.getServiceScoreMin(), dto.getServiceScoreMax(), "服务分");
        ParamValidator.validateNonNegativeRange(dto.getScoreBasisAmountMin(), dto.getScoreBasisAmountMax(), "评分样本金额");
        ParamValidator.validateNonNegativeRange(dto.getAvgDeliveryDaysMin(), dto.getAvgDeliveryDaysMax(), "平均到货周期");
    }

    /**
        * 实体转VO，评分字段从100倍存储值转为业务小数
      */
    private SupplierVo toVo(Supplier entity) {
        SupplierVo vo = new SupplierVo();
        vo.setSupplierId(entity.getId());
        vo.setSupplierCode(entity.getSupplierCode());
        vo.setSupplierName(entity.getSupplierName());
        vo.setContactName(entity.getContactName());
        vo.setContactPhone(entity.getContactPhone());
        vo.setAddress(entity.getAddress());
        vo.setPaymentTerms(entity.getPaymentTerms());
        vo.setOverallScore(QtyUtil.toDecimal(entity.getOverallScore()));
        vo.setDeliveryScore(QtyUtil.toDecimal(entity.getDeliveryScore()));
        vo.setQualityScore(QtyUtil.toDecimal(entity.getQualityScore()));
        vo.setPriceScore(QtyUtil.toDecimal(entity.getPriceScore()));
        vo.setServiceScore(QtyUtil.toDecimal(entity.getServiceScore()));
        vo.setServiceScoreReason(entity.getServiceScoreReason());
        vo.setAvgDeliveryDays(entity.getAvgDeliveryDays());
        vo.setScoreBasisAmount(QtyUtil.toDecimal(entity.getScoreBasisAmount()));
        vo.setScoreStatus(entity.getScoreStatus());
        vo.setStatus(entity.getStatus());
        vo.setVersion(entity.getVersion());
        vo.setRemark(entity.getRemark());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        vo.setUpdatedById(entity.getUpdatedById());
        vo.setUpdatedByName(entity.getUpdatedByName());
        return vo;
    }

    /**
     * 新增供应商
     * @param dto 新增供应商请求DTO
     * @return 供应商VO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SupplierVo create(SupplierCreateDto dto) {
        LoginUser currentUser = UserContext.requireCurrentUser();
        Supplier entity = new Supplier();
        entity.setSupplierCode(codeNoGenerator.nextNo(SUPPLIER_CODE, () -> supplierMapper.findMaxSupplierCodeSequence(
                SUPPLIER_CODE.prefix(), SUPPLIER_CODE.prefix().length(), SUPPLIER_CODE.width())));
        entity.setSupplierName(dto.getSupplierName().trim());
        entity.setContactName(normalizeOptionalText(dto.getContactName()));
        entity.setContactPhone(normalizeOptionalText(dto.getContactPhone()));
        entity.setAddress(normalizeOptionalText(dto.getAddress()));
        entity.setPaymentTerms(normalizeOptionalText(dto.getPaymentTerms()));
        validateInitialServiceScore(dto.getServiceScore(), dto.getServiceScoreReason());
        entity.setServiceScore(toStoredScore(dto.getServiceScore()));
        entity.setServiceScoreReason(dto.getServiceScore() == null ? "" : dto.getServiceScoreReason().trim());
        entity.setScoreBasisAmount(0L);
        entity.setScoreStatus(SupplierScoreStatus.NOT_READY.name());
        entity.setStatus(dto.getStatus());
        entity.setRemark(dto.getRemark());
        entity.setUpdatedById(currentUser.getUserId());
        entity.setUpdatedByName(currentUser.getRealName());
        supplierMapper.insert(entity);
        return toVo(supplierMapper.selectById(entity.getId()));
    }

    /**
     * 编辑供应商（乐观锁）
     * @param supplierId 供应商ID
     * @param dto 编辑供应商请求DTO
     * @return 供应商VO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SupplierVo update(Long supplierId, SupplierUpdateDto dto) {
        // 查询原记录，确认供应商仍存在
        Supplier existing = supplierMapper.selectById(supplierId);
        if (existing == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
        // 从启用切到停用时校验：供应商不能被未完成的业务单据引用
        if (Integer.valueOf(0).equals(dto.getStatus()) && !Integer.valueOf(0).equals(existing.getStatus())) {
            ensureCanDisable(supplierId);
        }
        Supplier entity = new Supplier();
        LoginUser currentUser = UserContext.requireCurrentUser();
        entity.setId(supplierId);
        entity.setSupplierCode(null);
        entity.setSupplierName(dto.getSupplierName().trim());
        entity.setContactName(normalizeOptionalText(dto.getContactName()));
        entity.setContactPhone(normalizeOptionalText(dto.getContactPhone()));
        entity.setAddress(normalizeOptionalText(dto.getAddress()));
        entity.setPaymentTerms(normalizeOptionalText(dto.getPaymentTerms()));
        entity.setStatus(dto.getStatus());
        entity.setRemark(dto.getRemark());
        entity.setVersion(dto.getVersion());
        entity.setUpdatedById(currentUser.getUserId());
        entity.setUpdatedByName(currentUser.getRealName());
        int rows = supplierMapper.updateById(entity);
        if (rows == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                    "供应商不存在或数据已发生变化，请刷新后重试");
        }
        return toVo(supplierMapper.selectById(supplierId));
    }

    /**
     * 更新供应商服务分（乐观锁）
     * @param supplierId 供应商ID
     * @param dto 更新供应商服务分请求DTO
     * @return 供应商VO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SupplierVo updateServiceScore(Long supplierId, SupplierServiceScoreDto dto) {
        Supplier existing = supplierMapper.selectById(supplierId);
        if (existing == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
        validateScorePrecision(dto.getServiceScore());
        String reason = dto.getReason().trim();
        Integer after = toStoredScore(dto.getServiceScore());
        Integer before = existing.getServiceScore();
        LoginUser currentUser = UserContext.requireCurrentUser();
        // 显式 LambdaUpdateWrapper：updateById 默认忽略 null；清空服务分必须显式 SET NULL，
        // 才能同时满足分数与原因的成组约束。
        // 服务分原因为 nullable：清空分数时原因也写 null，与 Schema 字段 nullable 对齐。
        LambdaUpdateWrapper<Supplier> update = new LambdaUpdateWrapper<Supplier>()
                .eq(Supplier::getId, supplierId)
                .eq(Supplier::getVersion, dto.getVersion())
                .set(Supplier::getServiceScore, after)
                .set(Supplier::getServiceScoreReason, after == null ? null : reason)
                .set(Supplier::getUpdatedById, currentUser.getUserId())
                .set(Supplier::getUpdatedByName, currentUser.getRealName())
                .set(Supplier::getVersion, dto.getVersion() + 1);
        if (supplierMapper.update(null, update) == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "供应商数据已变化，请刷新后重试");
        }
        if (!Objects.equals(before, after)) {
            supplierScoreChangeLogService.writeServiceScoreLog(supplierId, before, after, reason, currentUser);
        }
        // TODO(供应商评分第3期)：服务分变化后，在此同一事务内重算供应商总分和全部供货关系推荐分。
        return toVo(supplierMapper.selectById(supplierId));
    }

    /**
     * 校验初始服务分与服务分原因是否同时填写或同时为空。
     * <p>两者都为空代表不维护初始服务分,允许;仅填其一则视为参数缺失。</p>
     */
    private void validateInitialServiceScore(BigDecimal score, String reason) {
        boolean scoreProvided = score != null;
        boolean reasonProvided = reason != null && StrUtil.isNotBlank(reason);
        if (scoreProvided != reasonProvided) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "初始服务分与服务分原因必须同时填写或同时为空");
        }
        validateScorePrecision(score);
    }

    /**
     * 校验服务分是否保留两位小数
     */
    private void validateScorePrecision(BigDecimal score) {
        if (score != null && score.stripTrailingZeros().scale() > QtyUtil.SCALE) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "服务分最多保留两位小数");
        }
    }

    private Integer toStoredScore(BigDecimal score) {
        return QtyUtil.toStoredInt(score);
    }

    /** 可选基础资料统一保存为空字符串，兼容现有 NOT NULL DEFAULT '' 列定义。 */
    private String normalizeOptionalText(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 批量修改供应商状态（带乐观锁校验）。
     * <p>事务内执行,任意一条失败回滚整批;停用分支前置批量收集业务引用,
     * 把 N 条 × 3 次 selectCount 优化为 1 次批量查询,且 ensureCanDisable 复用
     * 已查到的结果,不再重复触发 SQL。</p>
     * @param dto 批量状态更新请求DTO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchUpdateStatus(SupplierBatchStatusDto dto) {
        LoginUser currentUser = UserContext.requireCurrentUser();
        List<Long> supplierIds = IdUtil.parseRequiredLongIds(dto.getSupplierIds(), "供应商ID");
        // 一次批量查询所有实体
        List<Supplier> entities = supplierMapper.selectByIds(supplierIds);
        Map<Long, Supplier> entityMap = entities.stream()
                .collect(Collectors.toMap(Supplier::getId, e -> e));
        boolean toDisable = Integer.valueOf(0).equals(dto.getStatus());
        // 停用时一次性批量收集待停用列表对应的业务引用,避免 N 次 3 连查
        DisableBlockReasons blockReasons = toDisable ? collectDisableBlockReasons(entityMap, supplierIds) : DisableBlockReasons.empty();
        for (int index = 0; index < dto.getSupplierIds().size(); index++) {
            String supplierId = dto.getSupplierIds().get(index);
            Long supplierIdLong = supplierIds.get(index);
            Supplier entity = entityMap.get(supplierIdLong);
            if (entity == null) {
                throw new BizException(ErrorCode.DATA_NOT_FOUND);
            }
            Integer expectedVersion = dto.getVersionBySupplierId().get(supplierId);
            if (expectedVersion == null) {
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "供应商" + supplierId + "缺少版本号");
            }
            if (!entity.getVersion().equals(expectedVersion)) {
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "供应商数据已被他人修改，请刷新后重试");
            }
            // 从启用切到停用时校验：供应商不能被未完成的业务单据引用
            if (toDisable && !Integer.valueOf(0).equals(entity.getStatus())) {
                blockReasons.assertDisableAllowed(supplierIdLong);
            }
            entity.setStatus(dto.getStatus());
            entity.setUpdatedById(currentUser.getUserId());
            entity.setUpdatedByName(currentUser.getRealName());
            supplierMapper.updateById(entity);
        }
    }

    /**
     * 批量收集待停用供应商在三类业务引用上的命中集合,供 batchUpdateStatus 复用。
     * 同一事务内只查一次,避免对每个 supplierId 重复触发 3 次 SQL。
     */
    private DisableBlockReasons collectDisableBlockReasons(Map<Long, Supplier> entityMap, List<Long> supplierIds) {
        List<Long> enableIds = supplierIds.stream()
                .map(entityMap::get)
                .filter(Objects::nonNull)
                .filter(e -> !Integer.valueOf(0).equals(e.getStatus()))
                .map(Supplier::getId)
                .toList();
        if (enableIds.isEmpty()) {
            return DisableBlockReasons.empty();
        }
        // 1. 供货产品持有者
        Set<Long> productHolders = supplierProductMapper.selectList(
                new LambdaQueryWrapper<SupplierProduct>()
                        .in(SupplierProduct::getSupplierId, enableIds)
                        .select(SupplierProduct::getSupplierId)
        ).stream().map(SupplierProduct::getSupplierId).collect(Collectors.toSet());
        // 2. 未完成采购订单持有者
        Set<Long> openOrderHolders = purchaseOrderMapper.selectList(
                new LambdaQueryWrapper<PurchaseOrder>()
                        .in(PurchaseOrder::getSupplierId, enableIds)
                        .notIn(PurchaseOrder::getStatus,
                                PurchaseOrderStatus.INBOUND_DONE.name(),
                                PurchaseOrderStatus.CANCELLED.name())
                        .select(PurchaseOrder::getSupplierId)
        ).stream().map(PurchaseOrder::getSupplierId).collect(Collectors.toSet());
        // 3. 未完成采购退货单持有者
        Set<Long> openReturnHolders = returnOrderMapper.selectList(
                new LambdaQueryWrapper<ReturnOrder>()
                        .in(ReturnOrder::getPartyId, enableIds)
                        .eq(ReturnOrder::getReturnType, ReturnType.PURCHASE_RETURN.name())
                        .notIn(ReturnOrder::getStatus,
                                ReturnStatus.COMPLETED.name(),
                                ReturnStatus.CANCELLED.name())
                        .select(ReturnOrder::getPartyId)
        ).stream().map(ReturnOrder::getPartyId).collect(Collectors.toSet());
        return new DisableBlockReasons(productHolders, openOrderHolders, openReturnHolders);
    }

    /**
     * 停用阻挡结果：按供货产品、采购订单、退货单三个维度缓存命中集合。
     * 供 batchUpdateStatus 在循环里复用,避免对同一 supplier 重复 SELECT。
     */
    private record DisableBlockReasons(Set<Long> productHolders,
                                       Set<Long> openOrderHolders,
                                       Set<Long> openReturnHolders) {
        static DisableBlockReasons empty() {
            return new DisableBlockReasons(Set.of(), Set.of(), Set.of());
        }

        void assertDisableAllowed(Long supplierId) {
            if (productHolders.contains(supplierId)) {
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "供应商存在供货产品，无法停用");
            }
            if (openOrderHolders.contains(supplierId)) {
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "供应商被未完成采购订单引用，无法停用");
            }
            if (openReturnHolders.contains(supplierId)) {
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "供应商被未完成业务引用，无法停用");
            }
        }
    }

    /**
     * 批量删除供应商（逻辑删除，最佳努力模式，乐观锁实现）。
     * <p>校验类失败(豆子、订单、退货单)整批回滚 409;乐观锁/版本号类失败单条跳过,
     * 返回结构化失败明细,避免此前 Map.toString() 拼接到 msg 不可解析的问题。</p>
     * @param dto 批量删除请求DTO
     * @return 失败明细；空列表表示全部成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<SupplierBatchFailure> batchDelete(SupplierBatchDeleteDto dto) {
        List<Long> supplierIds = IdUtil.parseRequiredLongIds(dto.getSupplierIds(), "供应商ID");
        List<SupplierBatchFailure> failures = new ArrayList<>();
        for (int index = 0; index < dto.getSupplierIds().size(); index++) {
            String supplierIdStr = dto.getSupplierIds().get(index);
            Long supplierId = supplierIds.get(index);
            Integer expectedVersion = dto.getVersionBySupplierId().get(supplierIdStr);
            if (expectedVersion == null) {
                failures.add(new SupplierBatchFailure(supplierIdStr, "未找到版本号"));
                continue;
            }
            ensureCanDelete(supplierId);
            int rows = supplierMapper.deleteByIdWithVersion(supplierId, expectedVersion);
            if (rows == 0) {
                failures.add(new SupplierBatchFailure(supplierIdStr, "供应商不存在或数据已发生变化，请刷新后重试"));
            }
        }
        return failures;
    }

    /**
     * 删除前校验：存在供货产品、采购订单或采购退货单时不允许删除（任意状态都会破坏追溯）。
     * @param supplierId 供应商ID
     */
    private void ensureCanDelete(Long supplierId) {
        // 1. 校验是否存在供货产品
        Long productCount = supplierProductMapper.selectCount(
                new LambdaQueryWrapper<SupplierProduct>()
                        .eq(SupplierProduct::getSupplierId, supplierId)
        );
        if (productCount > 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "供应商存在供货产品，无法删除");
        }
        // 2. 校验是否存在采购订单
        if (purchaseOrderMapper.selectCount(
                new LambdaQueryWrapper<PurchaseOrder>()
                        .eq(PurchaseOrder::getSupplierId, supplierId)
        ) > 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "供应商存在采购订单，无法删除");
        }
        // 3. 校验是否存在采购退货单
        if (returnOrderMapper.selectCount(
                new LambdaQueryWrapper<ReturnOrder>()
                        .eq(ReturnOrder::getPartyId, supplierId)
                        .eq(ReturnOrder::getReturnType, ReturnType.PURCHASE_RETURN.name())
        ) > 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "供应商存在业务引用，无法删除");
        }
    }

    /**
     * 停用前校验：存在未完成的采购订单或采购退货单时不允许停用。
     * <p>已完成（INBOUND_DONE / COMPLETED）或已取消的单据不阻挡停用。</p>
     * @param supplierId 供应商ID
     */
    private void ensureCanDisable(Long supplierId) {
        // 1. 校验是否存在供货产品
        Long productCount = supplierProductMapper.selectCount(
                new LambdaQueryWrapper<SupplierProduct>()
                        .eq(SupplierProduct::getSupplierId, supplierId)
        );
        if (productCount > 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "供应商存在供货产品，无法停用");
        }
        // 2. 校验是否存在采购未完成的采购订单
        if (purchaseOrderMapper.selectCount(
                new LambdaQueryWrapper<PurchaseOrder>()
                        .eq(PurchaseOrder::getSupplierId, supplierId)
                        .notIn(PurchaseOrder::getStatus,
                                PurchaseOrderStatus.INBOUND_DONE.name(),
                                PurchaseOrderStatus.CANCELLED.name())
        ) > 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "供应商被未完成采购订单引用，无法停用");
        }
        // 3. 校验是否存在采购未完成的采购退货单
        if (returnOrderMapper.selectCount(
                new LambdaQueryWrapper<ReturnOrder>()
                        .eq(ReturnOrder::getPartyId, supplierId)
                        .eq(ReturnOrder::getReturnType, ReturnType.PURCHASE_RETURN.name())
                        .notIn(ReturnOrder::getStatus,
                                ReturnStatus.COMPLETED.name(),
                                ReturnStatus.CANCELLED.name())
        ) > 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "供应商被未完成业务引用，无法停用");
        }
    }
}
