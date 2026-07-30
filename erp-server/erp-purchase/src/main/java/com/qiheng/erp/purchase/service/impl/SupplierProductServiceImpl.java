package com.qiheng.erp.purchase.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.util.IdUtil;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.product.mapper.ProductMapper;
import com.qiheng.erp.purchase.domain.supplierproduct.dto.SupplierProductBatchDeleteDto;
import com.qiheng.erp.purchase.domain.supplierproduct.dto.SupplierProductBatchStatusDto;
import com.qiheng.erp.purchase.domain.supplierproduct.dto.SupplierProductCreateDto;
import com.qiheng.erp.purchase.domain.supplierproduct.dto.SupplierProductPageDto;
import com.qiheng.erp.purchase.domain.supplier.entity.Supplier;
import com.qiheng.erp.purchase.domain.supplierproduct.entity.SupplierProduct;
import com.qiheng.erp.purchase.domain.supplierproduct.vo.SupplierProductVo;
import com.qiheng.erp.purchase.mapper.SupplierMapper;
import com.qiheng.erp.purchase.mapper.SupplierProductMapper;
import com.qiheng.erp.purchase.service.ISupplierProductService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 供应商供货产品表 服务实现类
 * </p>
 *
 * @author Li
 * @since 2026-07-29
 */
@Service
public class SupplierProductServiceImpl extends ServiceImpl<SupplierProductMapper, SupplierProduct> implements ISupplierProductService {

    @Autowired
    private SupplierProductMapper supplierProductMapper;

    @Autowired
    private SupplierMapper supplierMapper;

    @Autowired
    private ProductMapper productMapper;

    /**
     * 供货产品分页查询（关联供应商、产品主数据获取展示字段）
     * @param dto 分页查询参数DTO
     * @return 分页查询结果VO
     */
    @Override
    public PageResult<SupplierProductVo> page(SupplierProductPageDto dto) {
        Long supplierId = IdUtil.parseOptionalLongId(dto.getSupplierId(), "供应商ID");
        MPJLambdaWrapper<SupplierProduct> wrapper = buildBaseWrapper()
                .eq(supplierId != null, SupplierProduct::getSupplierId, supplierId)
                .like(StrUtil.isNotBlank(dto.getProductCode()), Product::getProductCode, dto.getProductCode())
                .like(StrUtil.isNotBlank(dto.getProductName()), Product::getProductName, dto.getProductName())
                .eq(dto.getStatus() != null, SupplierProduct::getStatus, dto.getStatus())
                .orderByDesc(SupplierProduct::getCreateTime);
        Page<SupplierProductVo> result = supplierProductMapper.selectJoinPage(dto.toPage(), SupplierProductVo.class, wrapper);
        result.getRecords().forEach(this::convertStoredValues);
        return PageResult.of(
                result.getRecords(),
                (int) result.getTotal(),
                (int) result.getCurrent(),
                (int) result.getSize()
        );
    }

    /**
     * 新增供货产品（仅保存关系自身的业务属性）
     * @param dto 新增供货产品请求DTO
     * @return 供货产品VO
     */
    @Override
    public SupplierProductVo create(SupplierProductCreateDto dto) {
        Long supplierId = IdUtil.parseRequiredLongId(dto.getSupplierId(), "供应商ID");
        Long productId = IdUtil.parseRequiredLongId(dto.getProductId(), "产品ID");
        // 检查唯一约束（供应商+产品）
        Long existCount = supplierProductMapper.selectCount(
                new LambdaQueryWrapper<SupplierProduct>()
                        .eq(SupplierProduct::getSupplierId, supplierId)
                        .eq(SupplierProduct::getProductId, productId)
        );
        if (existCount > 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "该供应商已存在此产品的供货关系");
        }
        // 校验关联的供应商、产品存在后，保存供货关系自身字段
        SupplierProduct entity = buildEntityFromDto(dto);
        supplierProductMapper.insert(entity);
        return toVo(entity.getId());
    }

    /**
     * 编辑供货产品（重新校验供应商和产品，并校验唯一性）
     * @param supplierProductId 供货产品ID
     * @param dto 编辑供货产品请求DTO
     * @return 供货产品VO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SupplierProductVo update(Long supplierProductId, SupplierProductCreateDto dto) {
        // 校验版本号
        if (dto.getVersion() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "编辑时版本号不能为空");
        }
        // 查询原记录
        SupplierProduct existing = supplierProductMapper.selectById(supplierProductId);
        if (existing == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "供货产品不存在");
        }
        Long supplierId = IdUtil.parseRequiredLongId(dto.getSupplierId(), "供应商ID");
        Long productId = IdUtil.parseRequiredLongId(dto.getProductId(), "产品ID");
        // 如果更换了供应商或产品，校验唯一性
        if (!supplierId.equals(existing.getSupplierId()) || !productId.equals(existing.getProductId())) {
            Long existCount = supplierProductMapper.selectCount(
                    new LambdaQueryWrapper<SupplierProduct>()
                            .eq(SupplierProduct::getSupplierId, supplierId)
                            .eq(SupplierProduct::getProductId, productId)
                            .ne(SupplierProduct::getId, supplierProductId)
            );
            if (existCount > 0) {
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "该供应商已存在此产品的供货关系");
            }
        }
        // 构建更新实体；供应商和产品展示字段由查询时关联主数据取得
        SupplierProduct entity = buildEntityFromDto(dto);
        entity.setId(supplierProductId);
        entity.setVersion(dto.getVersion());
        int rows = supplierProductMapper.updateById(entity);
        if (rows == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "数据已被修改，请刷新后重试");
        }
        return toVo(supplierProductId);
    }

    /**
     * 根据DTO构建供货产品实体，并校验关联主数据存在
     * @param dto 供货产品请求DTO
     * @return 供货关系实体
     */
    private SupplierProduct buildEntityFromDto(SupplierProductCreateDto dto) {
        Long supplierId = IdUtil.parseRequiredLongId(dto.getSupplierId(), "供应商ID");
        Long productId = IdUtil.parseRequiredLongId(dto.getProductId(), "产品ID");
        if (supplierMapper.selectById(supplierId) == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "供应商不存在");
        }
        if (productMapper.selectById(productId) == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "产品不存在");
        }
        SupplierProduct entity = new SupplierProduct();
        entity.setSupplierId(supplierId);
        entity.setProductId(productId);
        entity.setSupplierProductCode(dto.getSupplierProductCode());
        // latestPurchasePrice 可为 null，表示尚未采购过
        Long storedPrice = QtyUtil.toStored(dto.getLatestPurchasePrice());
        entity.setLatestPurchasePrice(storedPrice != null ? storedPrice.intValue() : null);
        entity.setMinOrderQty(QtyUtil.toStored(dto.getMinOrderQty()).intValue());
        entity.setLeadTimeDays(dto.getLeadTimeDays());
        entity.setDeliveryScore(QtyUtil.toStored(dto.getDeliveryScore()).intValue());
        entity.setQualityScore(QtyUtil.toStored(dto.getQualityScore()).intValue());
        entity.setPriceScore(QtyUtil.toStored(dto.getPriceScore()).intValue());
        entity.setAiScore(QtyUtil.toStored(dto.getAiScore()).intValue());
        entity.setStatus(dto.getStatus());
        entity.setRemark(dto.getRemark());
        return entity;
    }

    /**
     * 根据ID查询供货产品VO（关联供应商、产品主数据）
     * @param id 供货产品ID
     * @return 供货产品VO
     */
    private SupplierProductVo toVo(Long id) {
        MPJLambdaWrapper<SupplierProduct> wrapper = buildBaseWrapper()
                .eq(SupplierProduct::getId, id);
        SupplierProductVo vo = supplierProductMapper.selectJoinOne(SupplierProductVo.class, wrapper);
        if (vo != null) {
            convertStoredValues(vo);
        }
        return vo;
    }

    /**
     * 构建供货产品VO查询基础Wrapper（展示字段实时关联供应商、产品主数据）
     * @return 基础查询Wrapper
     */
    private MPJLambdaWrapper<SupplierProduct> buildBaseWrapper() {
        return new MPJLambdaWrapper<SupplierProduct>()
                .selectAs(SupplierProduct::getId, SupplierProductVo::getSupplierProductId)
                .select(SupplierProduct::getSupplierId)
                .selectAs(Supplier::getSupplierCode, SupplierProductVo::getSupplierCode)
                .selectAs(Supplier::getSupplierName, SupplierProductVo::getSupplierName)
                .select(SupplierProduct::getProductId)
                .selectAs(Product::getProductCode, SupplierProductVo::getProductCode)
                .selectAs(Product::getProductName, SupplierProductVo::getProductName)
                .selectAs(Product::getUnitName, SupplierProductVo::getUnitName)
                .selectAs(Product::getQuantityPrecision, SupplierProductVo::getQuantityPrecision)
                .select(SupplierProduct::getSupplierProductCode)
                .select(SupplierProduct::getLatestPurchasePrice)
                .select(SupplierProduct::getMinOrderQty)
                .select(SupplierProduct::getLeadTimeDays)
                .select(SupplierProduct::getDeliveryScore)
                .select(SupplierProduct::getQualityScore)
                .select(SupplierProduct::getPriceScore)
                .select(SupplierProduct::getAiScore)
                .select(SupplierProduct::getLastPurchaseAt)
                .select(SupplierProduct::getStatus)
                .select(SupplierProduct::getVersion)
                .select(SupplierProduct::getRemark)
                .select(SupplierProduct::getCreateTime)
                .select(SupplierProduct::getUpdateTime)
                .leftJoin(Supplier.class, Supplier::getId, SupplierProduct::getSupplierId)
                .leftJoin(Product.class, Product::getId, SupplierProduct::getProductId);
    }

    /**
     * 100倍存储字段转业务值（评分÷100，单价÷100，起订量÷100）
     * @param vo 供货产品VO
     */
    private void convertStoredValues(SupplierProductVo vo) {
        vo.setDeliveryScore(QtyUtil.toDecimal(vo.getDeliveryScore()));
        vo.setQualityScore(QtyUtil.toDecimal(vo.getQualityScore()));
        vo.setPriceScore(QtyUtil.toDecimal(vo.getPriceScore()));
        vo.setAiScore(QtyUtil.toDecimal(vo.getAiScore()));
        vo.setLatestPurchasePrice(QtyUtil.toDecimal(vo.getLatestPurchasePrice()));
        vo.setMinOrderQty(QtyUtil.toDecimal(vo.getMinOrderQty()));
    }

    /**
     * 批量修改供货产品状态（最佳努力模式，乐观锁实现）
     * @param dto 批量状态修改请求DTO
     * @return 失败的供货产品信息：key=供货产品ID，value=失败原因；空 map 表示全部成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, String> batchUpdateStatus(SupplierProductBatchStatusDto dto) {
        Map<String, String> failures = new LinkedHashMap<>();
        List<String> ids = dto.getSupplierProductIds();
        List<Long> supplierProductIds = IdUtil.parseRequiredLongIds(ids, "供货关系ID");
        Map<String, Integer> versionMap = dto.getVersionBySupplierProductId();
        Integer targetStatus = dto.getStatus();
        // 遍历供货产品ID列表，更新状态
        for (int index = 0; index < ids.size(); index++) {
            String id = ids.get(index);
            Integer expectedVersion = versionMap.get(id);
            if (expectedVersion == null) {
                failures.put(id, "未找到版本号");
                continue;
            }
            Long supplierProductId = supplierProductIds.get(index);
            SupplierProduct entity = new SupplierProduct();
            entity.setId(supplierProductId);
            entity.setStatus(targetStatus);
            entity.setVersion(expectedVersion);
            int rows = supplierProductMapper.updateById(entity);
            if (rows == 0) {
                failures.put(id, "供货产品不存在或数据已发生变化，请刷新后重试");
            }
        }

        return failures;
    }

    /**
     * 批量删除供货产品（逻辑删除，最佳努力模式，乐观锁实现）
     * @param dto 批量删除请求DTO
     * @return 失败的供货产品信息：key=供货产品ID，value=失败原因；空 map 表示全部成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, String> batchDelete(SupplierProductBatchDeleteDto dto) {
        Map<String, String> failures = new LinkedHashMap<>();
        List<String> ids = dto.getSupplierProductIds();
        List<Long> supplierProductIds = IdUtil.parseRequiredLongIds(ids, "供货关系ID");
        for (int index = 0; index < ids.size(); index++) {
            String idStr = ids.get(index);
            Long supplierProductId = supplierProductIds.get(index);
            Integer expectedVersion = dto.getVersionBySupplierProductId().get(idStr);
            if (expectedVersion == null) {
                failures.put(idStr, "未找到版本号");
                continue;
            }
            int rows = supplierProductMapper.deleteByIdWithVersion(supplierProductId, expectedVersion);
            if (rows == 0) {
                failures.put(idStr, "供货产品不存在或数据已发生变化，请刷新后重试");
            }
        }
        return failures;
    }
}
