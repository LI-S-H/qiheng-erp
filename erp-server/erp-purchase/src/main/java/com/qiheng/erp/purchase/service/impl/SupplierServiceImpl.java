package com.qiheng.erp.purchase.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.purchase.domain.dto.SupplierBatchDeleteDto;
import com.qiheng.erp.purchase.domain.dto.SupplierBatchStatusDto;
import com.qiheng.erp.purchase.domain.dto.SupplierCreateDto;
import com.qiheng.erp.purchase.domain.dto.SupplierPageDto;
import com.qiheng.erp.purchase.domain.dto.SupplierUpdateDto;
import com.qiheng.erp.purchase.domain.entity.Supplier;
import com.qiheng.erp.purchase.domain.entity.SupplierProduct;
import com.qiheng.erp.purchase.domain.vo.SupplierVo;
import com.qiheng.erp.purchase.mapper.SupplierMapper;
import com.qiheng.erp.purchase.mapper.SupplierProductMapper;
import com.qiheng.erp.purchase.service.ISupplierService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
        vo.setOverallScore(scoreToDecimal(entity.getOverallScore()));
        vo.setDeliveryScore(scoreToDecimal(entity.getDeliveryScore()));
        vo.setQualityScore(scoreToDecimal(entity.getQualityScore()));
        vo.setPriceScore(scoreToDecimal(entity.getPriceScore()));
        vo.setServiceScore(scoreToDecimal(entity.getServiceScore()));
        vo.setAvgDeliveryDays(entity.getAvgDeliveryDays());
        vo.setOnTimeRate(scoreToDecimal(entity.getOnTimeRate()));
        vo.setQualifiedRate(scoreToDecimal(entity.getQualifiedRate()));
        vo.setStatus(entity.getStatus());
        vo.setVersion(entity.getVersion());
        vo.setRemark(entity.getRemark());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        return vo;
    }

    /**
     * 评分/百分率整数转业务小数（存储值 ÷ 100）
     */
    private BigDecimal scoreToDecimal(Integer score) {
        if (score == null) {
            return null;
        }
        return QtyUtil.toDecimal(BigDecimal.valueOf(score));
    }

    /**
     * 新增供应商
     * @param dto 新增供应商请求DTO
     * @return 供应商VO
     */
    @Override
    public SupplierVo create(SupplierCreateDto dto) {
        Supplier entity = new Supplier();
        entity.setSupplierCode(generateSupplierCode());
        entity.setSupplierName(dto.getSupplierName());
        entity.setContactName(dto.getContactName());
        entity.setContactPhone(dto.getContactPhone());
        entity.setAddress(dto.getAddress());
        entity.setPaymentTerms(dto.getPaymentTerms());
        entity.setOverallScore(scoreToStored(dto.getOverallScore()));
        entity.setDeliveryScore(scoreToStored(dto.getDeliveryScore()));
        entity.setQualityScore(scoreToStored(dto.getQualityScore()));
        entity.setPriceScore(scoreToStored(dto.getPriceScore()));
        entity.setServiceScore(scoreToStored(dto.getServiceScore()));
        entity.setAvgDeliveryDays(dto.getAvgDeliveryDays());
        entity.setOnTimeRate(scoreToStored(dto.getOnTimeRate()));
        entity.setQualifiedRate(scoreToStored(dto.getQualifiedRate()));
        entity.setStatus(dto.getStatus());
        entity.setRemark(dto.getRemark());
        supplierMapper.insert(entity);
        return toVo(supplierMapper.selectById(entity.getId()));
    }

    /**
     * 编辑供应商（乐观锁，供应商名称变更时同步供货产品快照）
     * @param supplierId 供应商ID
     * @param dto 编辑供应商请求DTO
     * @return 供应商VO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SupplierVo update(Long supplierId, SupplierUpdateDto dto) {
        // 查询原记录，用于对比名称是否变更
        Supplier old = supplierMapper.selectById(supplierId);
        if (old == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
        Supplier entity = new Supplier();
        entity.setId(supplierId);
        entity.setSupplierCode(null);
        entity.setSupplierName(dto.getSupplierName());
        entity.setContactName(dto.getContactName());
        entity.setContactPhone(dto.getContactPhone());
        entity.setAddress(dto.getAddress());
        entity.setPaymentTerms(dto.getPaymentTerms());
        entity.setOverallScore(scoreToStored(dto.getOverallScore()));
        entity.setDeliveryScore(scoreToStored(dto.getDeliveryScore()));
        entity.setQualityScore(scoreToStored(dto.getQualityScore()));
        entity.setPriceScore(scoreToStored(dto.getPriceScore()));
        entity.setServiceScore(scoreToStored(dto.getServiceScore()));
        entity.setAvgDeliveryDays(dto.getAvgDeliveryDays());
        entity.setOnTimeRate(scoreToStored(dto.getOnTimeRate()));
        entity.setQualifiedRate(scoreToStored(dto.getQualifiedRate()));
        entity.setStatus(dto.getStatus());
        entity.setRemark(dto.getRemark());
        entity.setVersion(dto.getVersion());
        int rows = supplierMapper.updateById(entity);
        if (rows == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                    "供应商不存在或数据已发生变化，请刷新后重试");
        }
        // 供应商名称变更时，同步供货产品中的冗余供应商名称
        if (!dto.getSupplierName().equals(old.getSupplierName())) {
            supplierProductMapper.update(null, new LambdaUpdateWrapper<SupplierProduct>()
                    .eq(SupplierProduct::getSupplierId, supplierId)
                    .set(SupplierProduct::getSupplierName, dto.getSupplierName()));
        }
        return toVo(supplierMapper.selectById(supplierId));
    }

    /**
     * 批量修改供应商状态（带乐观锁校验）
     * @param dto 批量状态更新请求DTO
     */
    @Override
    public void batchUpdateStatus(SupplierBatchStatusDto dto) {
        // 一次批量查询所有实体
        List<Supplier> entities = supplierMapper.selectByIds(dto.getSupplierIds());
        Map<Long, Supplier> entityMap = entities.stream()
                .collect(Collectors.toMap(Supplier::getId, e -> e));
        for (String supplierId : dto.getSupplierIds()) {
            Supplier entity = entityMap.get(Long.parseLong(supplierId));
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
            entity.setStatus(dto.getStatus());
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
        Map<String, String> failures = new LinkedHashMap<>();
        for (String supplierIdStr : dto.getSupplierIds()) {
            Long supplierId = Long.parseLong(supplierIdStr);
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
     * 删除前校验：存在供货产品时不允许删除
     * @param supplierId 供应商ID
     */
    private void ensureCanDelete(Long supplierId) {
        Long productCount = supplierProductMapper.selectCount(
                new LambdaQueryWrapper<SupplierProduct>()
                        .eq(SupplierProduct::getSupplierId, supplierId)
        );
        if (productCount > 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "供应商存在供货产品，无法删除");
        }
        // TODO 采购订单模块完成后，补充检查供应商是否存在采购订单，存在则拒绝删除
    }

    /**
     * 生成供应商编码
     * @return 供应商编码
     */
    private String generateSupplierCode() {
        Long seq = stringRedisTemplate.opsForValue().increment("supplier:code");
        return "S" + String.format("%04d", seq);
    }

    /**
     * 业务小数转100倍存储值（如 89.75 → 8975）
     */
    private Integer scoreToStored(BigDecimal score) {
        if (score == null) {
            return null;
        }
        return score.multiply(BigDecimal.valueOf(100)).intValue();
    }
}
