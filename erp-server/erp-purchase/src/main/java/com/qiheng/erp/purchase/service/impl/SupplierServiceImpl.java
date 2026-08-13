package com.qiheng.erp.purchase.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.util.CodeGen;
import com.qiheng.erp.common.util.IdUtil;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.domain.purchaseorder.enums.PurchaseOrderStatus;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierBatchDeleteDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierBatchStatusDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierCreateDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierPageDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierUpdateDto;
import com.qiheng.erp.purchase.domain.supplier.entity.Supplier;
import com.qiheng.erp.purchase.domain.supplierproduct.entity.SupplierProduct;
import com.qiheng.erp.purchase.domain.supplier.vo.SupplierVo;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.purchase.mapper.SupplierMapper;
import com.qiheng.erp.purchase.mapper.SupplierProductMapper;
import com.qiheng.erp.purchase.service.ISupplierService;
import com.qiheng.erp.returnorder.domain.entity.ReturnOrder;
import com.qiheng.erp.returnorder.domain.enums.ReturnStatus;
import com.qiheng.erp.returnorder.domain.port.ReturnType;
import com.qiheng.erp.returnorder.mapper.ReturnOrderMapper;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 供应商分页查询
     * @param dto 分页查询参数DTO
     * @return 分页查询结果VO
     */
    @Override
    public PageResult<SupplierVo> page(SupplierPageDto dto) {
        LambdaQueryWrapper<Supplier> wrapper = new LambdaQueryWrapper<Supplier>()
                .like(StrUtil.isNotBlank(dto.getSupplierCode()), Supplier::getSupplierCode, dto.getSupplierCode())
                .like(StrUtil.isNotBlank(dto.getSupplierName()), Supplier::getSupplierName, dto.getSupplierName())
                .like(StrUtil.isNotBlank(dto.getContactName()), Supplier::getContactName, dto.getContactName())
                .eq(dto.getStatus() != null, Supplier::getStatus, dto.getStatus())
                .orderByDesc(Supplier::getCreateTime);
        Page<Supplier> result = supplierMapper.selectPage(dto.toPage(), wrapper);
        return PageResult.of(
                result.getRecords().stream().map(this::toVo).toList(),
                (int) result.getTotal(),
                (int) result.getCurrent(),
                (int) result.getSize()
        );
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
        vo.setAvgDeliveryDays(entity.getAvgDeliveryDays());
        vo.setOnTimeRate(QtyUtil.toDecimal(entity.getOnTimeRate()));
        vo.setQualifiedRate(QtyUtil.toDecimal(entity.getQualifiedRate()));
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
    public SupplierVo create(SupplierCreateDto dto) {
        LoginUser currentUser = UserContext.requireCurrentUser();
        Supplier entity = new Supplier();
        entity.setSupplierCode(CodeGen.next(stringRedisTemplate, "supplier:code", "S", 4));
        entity.setSupplierName(dto.getSupplierName());
        entity.setContactName(dto.getContactName());
        entity.setContactPhone(dto.getContactPhone());
        entity.setAddress(dto.getAddress());
        entity.setPaymentTerms(dto.getPaymentTerms());
        entity.setOverallScore(QtyUtil.toStoredInt(dto.getOverallScore()));
        entity.setDeliveryScore(QtyUtil.toStoredInt(dto.getDeliveryScore()));
        entity.setQualityScore(QtyUtil.toStoredInt(dto.getQualityScore()));
        entity.setPriceScore(QtyUtil.toStoredInt(dto.getPriceScore()));
        entity.setServiceScore(QtyUtil.toStoredInt(dto.getServiceScore()));
        entity.setAvgDeliveryDays(dto.getAvgDeliveryDays());
        entity.setOnTimeRate(QtyUtil.toStoredInt(dto.getOnTimeRate()));
        entity.setQualifiedRate(QtyUtil.toStoredInt(dto.getQualifiedRate()));
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
        entity.setSupplierName(dto.getSupplierName());
        entity.setContactName(dto.getContactName());
        entity.setContactPhone(dto.getContactPhone());
        entity.setAddress(dto.getAddress());
        entity.setPaymentTerms(dto.getPaymentTerms());
        entity.setOverallScore(QtyUtil.toStoredInt(dto.getOverallScore()));
        entity.setDeliveryScore(QtyUtil.toStoredInt(dto.getDeliveryScore()));
        entity.setQualityScore(QtyUtil.toStoredInt(dto.getQualityScore()));
        entity.setPriceScore(QtyUtil.toStoredInt(dto.getPriceScore()));
        entity.setServiceScore(QtyUtil.toStoredInt(dto.getServiceScore()));
        entity.setAvgDeliveryDays(dto.getAvgDeliveryDays());
        entity.setOnTimeRate(QtyUtil.toStoredInt(dto.getOnTimeRate()));
        entity.setQualifiedRate(QtyUtil.toStoredInt(dto.getQualifiedRate()));
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
     * 批量修改供应商状态（带乐观锁校验）
     * @param dto 批量状态更新请求DTO
     */
    @Override
    public void batchUpdateStatus(SupplierBatchStatusDto dto) {
        LoginUser currentUser = UserContext.requireCurrentUser();
        List<Long> supplierIds = IdUtil.parseRequiredLongIds(dto.getSupplierIds(), "供应商ID");
        // 一次批量查询所有实体
        List<Supplier> entities = supplierMapper.selectByIds(supplierIds);
        Map<Long, Supplier> entityMap = entities.stream()
                .collect(Collectors.toMap(Supplier::getId, e -> e));
        for (int index = 0; index < dto.getSupplierIds().size(); index++) {
            String supplierId = dto.getSupplierIds().get(index);
            Supplier entity = entityMap.get(supplierIds.get(index));
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
            if (Integer.valueOf(0).equals(dto.getStatus()) && !Integer.valueOf(0).equals(entity.getStatus())) {
                ensureCanDisable(supplierIds.get(index));
            }
            entity.setStatus(dto.getStatus());
            entity.setUpdatedById(currentUser.getUserId());
            entity.setUpdatedByName(currentUser.getRealName());
            supplierMapper.updateById(entity);
        }
    }

    /**
     * 批量删除供应商（逻辑删除，最佳努力模式，乐观锁实现）
     * @param dto 批量删除请求DTO
     * @return 失败的供应商信息：key=供应商ID，value=失败原因；空 map 表示全部成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, String> batchDelete(SupplierBatchDeleteDto dto) {
        List<Long> supplierIds = IdUtil.parseRequiredLongIds(dto.getSupplierIds(), "供应商ID");
        Map<String, String> failures = new LinkedHashMap<>();
        for (int index = 0; index < dto.getSupplierIds().size(); index++) {
            String supplierIdStr = dto.getSupplierIds().get(index);
            Long supplierId = supplierIds.get(index);
            Integer expectedVersion = dto.getVersionBySupplierId().get(supplierIdStr);
            if (expectedVersion == null) {
                failures.put(supplierIdStr, "未找到版本号");
                continue;
            }
            ensureCanDelete(supplierId);
            int rows = supplierMapper.deleteByIdWithVersion(supplierId, expectedVersion);
            if (rows == 0) {
                failures.put(supplierIdStr, "供应商不存在或数据已发生变化，请刷新后重试");
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
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "供应商存在供货产品，无法删除");
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