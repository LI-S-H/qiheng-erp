package com.qiheng.erp.product.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.qiheng.erp.common.annotation.DistributedLock;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.product.domain.dto.ProductBatchStatusDto;
import com.qiheng.erp.product.domain.dto.ProductPageDto;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.product.domain.entity.ProductCategory;
import com.qiheng.erp.product.domain.vo.ProductVo;
import com.qiheng.erp.product.mapper.ProductMapper;
import com.qiheng.erp.product.service.IProductService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * <p>
 * 产品表 服务实现类
 * </p>
 *
 * @author Li
 * @since 2026-06-28
 */
@Service
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements IProductService {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 产品分页查询
     * @param dto 分页查询参数DTO
     * @return 分页查询结果VO
     */
    @Override
    public PageResult<ProductVo> page(ProductPageDto dto) {
        MPJLambdaWrapper<Product> wrapper = new MPJLambdaWrapper<Product>()
                .selectAs(Product::getId, ProductVo::getProductId)
                .select(Product::getProductCode)
                .select(Product::getProductName)
                .select(Product::getCategoryId)
                .selectAs(ProductCategory::getCategoryName, ProductVo::getCategoryName)
                .select(Product::getBrandName)
                .select(Product::getUnitName)
                .select(Product::getQuantityPrecision)
                .select(Product::getSpecification)
                .select(Product::getBarcode)
                .select(Product::getReferencePurchasePrice)
                .select(Product::getReferenceSalePrice)
                .select(Product::getSafetyStockQty)
                .select(Product::getStatus)
                .select(Product::getRemark)
                .select(Product::getCreateTime)
                .select(Product::getUpdateTime)
                .leftJoin(ProductCategory.class, ProductCategory::getId, Product::getCategoryId)
                .like(StrUtil.isNotBlank(dto.getProductCode()), Product::getProductCode, dto.getProductCode())
                .like(StrUtil.isNotBlank(dto.getProductName()), Product::getProductName, dto.getProductName())
                .eq(dto.getCategoryId() != null, Product::getCategoryId, dto.getCategoryId())
                .like(StrUtil.isNotBlank(dto.getBrandName()), Product::getBrandName, dto.getBrandName())
                .like(StrUtil.isNotBlank(dto.getBarcode()), Product::getBarcode, dto.getBarcode())
                .eq(dto.getStatus() != null, Product::getStatus, dto.getStatus())
                .orderByDesc(Product::getCreateTime);
        Page<ProductVo> result = productMapper.selectJoinPage(dto.toPage(), ProductVo.class, wrapper);
        result.getRecords().forEach(vo -> {
            if (vo.getSafetyStockQty() != null) {
                vo.setSafetyStockQty(vo.getSafetyStockQty().divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
            }
        });
        return PageResult.of(result.getRecords(), (int) result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    /**
     * 产品新增
     * @param product 产品实体
     * @return 产品VO
     */
    @Override
    public ProductVo add(Product product) {
        product.setProductCode(generateProductCode());
        if (product.getSafetyStockQty() != null) {
            product.setSafetyStockQty(
                    product.getSafetyStockQty().multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP)
            );
        }
        productMapper.insert(product);
        return getDetailById(product.getId());
    }

    /**
     * 根据ID查询产品详情
     * @param id 产品ID
     * @return 产品VO
     */
    @Override
    public ProductVo getDetailById(Long id) {
        MPJLambdaWrapper<Product> wrapper = new MPJLambdaWrapper<Product>()
                .selectAs(Product::getId, ProductVo::getProductId)
                .select(Product::getProductCode)
                .select(Product::getProductName)
                .select(Product::getCategoryId)
                .selectAs(ProductCategory::getCategoryName, ProductVo::getCategoryName)
                .select(Product::getBrandName)
                .select(Product::getUnitName)
                .select(Product::getQuantityPrecision)
                .select(Product::getSpecification)
                .select(Product::getBarcode)
                .select(Product::getReferencePurchasePrice)
                .select(Product::getReferenceSalePrice)
                .select(Product::getSafetyStockQty)
                .select(Product::getStatus)
                .select(Product::getRemark)
                .select(Product::getCreateTime)
                .select(Product::getUpdateTime)
                .leftJoin(ProductCategory.class, ProductCategory::getId, Product::getCategoryId)
                .eq(Product::getId, id);
        ProductVo vo = productMapper.selectJoinOne(ProductVo.class, wrapper);
        if (vo != null && vo.getSafetyStockQty() != null) {
            vo.setSafetyStockQty(vo.getSafetyStockQty().divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        }
        return vo;
    }


    /**
     * 生成产品编码
     * @return 产品编码
     */
    private String generateProductCode() {
        Long seq = stringRedisTemplate.opsForValue().increment("product:code");
        return "P" + String.format("%06d", seq);
    }

    /**
     * 批量更新产品状态
     * @param dto 批量更新产品状态参数DTO
     */
    @DistributedLock(key = "'product:lock:global'")
    @Override
    public void updateBatchStatus(ProductBatchStatusDto dto) {
        LambdaUpdateWrapper<Product> updateWrapper = new LambdaUpdateWrapper<Product>()
                .set(Product::getStatus, dto.getStatus())
                .in(Product::getId, dto.getProductIds());
        productMapper.update(null, updateWrapper);
    }

    /**
     * 更新产品状态
     * @param productId 产品ID
     * @param status 状态
     */
    @DistributedLock(key = "'product:lock:global'")
    @Override
    public void updateStatus(Long productId, Integer status) {
        LambdaUpdateWrapper<Product> updateWrapper = new LambdaUpdateWrapper<Product>()
                .set(Product::getStatus, status)
                .eq(Product::getId, productId);
        productMapper.update(null, updateWrapper);
    }



}