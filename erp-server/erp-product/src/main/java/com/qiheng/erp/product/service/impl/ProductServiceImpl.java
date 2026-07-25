package com.qiheng.erp.product.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.qiheng.erp.common.annotation.DistributedLock;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.product.domain.dto.ProductBatchStatusDto;
import com.qiheng.erp.product.domain.dto.ProductPageDto;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.product.domain.entity.ProductCategory;
import com.qiheng.erp.product.domain.vo.ProductVo;
import com.qiheng.erp.product.mapper.ProductCategoryMapper;
import com.qiheng.erp.product.mapper.ProductMapper;
import com.qiheng.erp.product.service.IProductService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
    private ProductCategoryMapper productCategoryMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;
    /**
     * 产品分页查询
     * @param dto 分页查询参数DTO
     * @return 分页查询结果VO
     */
    @Override
    public PageResult<ProductVo> page(ProductPageDto dto) {
        // 如果指定了分类ID，查询该分类及所有子分类的ID
        Set<Long> categoryIds = null;
        if (dto.getCategoryId() != null) {
            categoryIds = new HashSet<>();
            collectChildCategoryIds(dto.getCategoryId(), categoryIds);
        }

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
                .in(categoryIds != null, Product::getCategoryId, categoryIds)
                .like(StrUtil.isNotBlank(dto.getBrandName()), Product::getBrandName, dto.getBrandName())
                .like(StrUtil.isNotBlank(dto.getBarcode()), Product::getBarcode, dto.getBarcode())
                .eq(dto.getStatus() != null, Product::getStatus, dto.getStatus())
                .orderByDesc(Product::getCreateTime);
        Page<ProductVo> result = productMapper.selectJoinPage(dto.toPage(), ProductVo.class, wrapper);
        result.getRecords().forEach(vo -> {
            if (vo.getSafetyStockQty() != null) {
                vo.setSafetyStockQty(QtyUtil.toDecimal(vo.getSafetyStockQty()));
            }
        });
        return PageResult.of(result.getRecords(), (int) result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    private void collectChildCategoryIds(Long parentId, Set<Long> categoryIds) {
        // 一次性查出所有启用分类
        List<ProductCategory> allCategories = productCategoryMapper.selectList(new LambdaQueryWrapper<>());
        // 构建 parentId -> List<childId> 映射表
        Map<Long, List<Long>> parentChildMap = allCategories.stream()
                .collect(Collectors.groupingBy(ProductCategory::getParentId,
                        Collectors.mapping(ProductCategory::getId, Collectors.toList())));
        categoryIds.add(parentId);
        collectChildIdsFromMap(parentId, parentChildMap, categoryIds);
    }

    /**
     * 递归查询所有子分类ID
     * @param parentId 父分类ID
     * @param parentChildMap parentId -> List<childId> 映射表
     * @param result 子分类ID集合
     */
    private void collectChildIdsFromMap(Long parentId, Map<Long, List<Long>> parentChildMap, Set<Long> result) {
        List<Long> children = parentChildMap.get(parentId);
        if (children != null) {
            for (Long childId : children) {
                result.add(childId);
                collectChildIdsFromMap(childId, parentChildMap, result);
            }
        }
    }

    /**
     * 产品新增
     * @param product 产品实体
     * @return 产品VO
     */
    @Override
    @DistributedLock(key ="'product:category:global'",waitTime = 5,leaseTime = 10,timeUnit = TimeUnit.SECONDS)
    public ProductVo add(Product product) {
        product.setProductCode(generateProductCode());
        if (product.getSafetyStockQty() != null) {
            product.setSafetyStockQty(BigDecimal.valueOf(QtyUtil.toStored(product.getSafetyStockQty())));
        }
        // 校验分类是否存在且状态正常
        ProductCategory category = productCategoryMapper.selectById(product.getCategoryId());
        if (category == null || category.getStatus() != 1) {
            throw new BizException(ErrorCode.CATEGORY_ERROR);
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
            vo.setSafetyStockQty(QtyUtil.toDecimal(vo.getSafetyStockQty()));
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
    @DistributedLock(key = "'product:lock:' + #productId")
    @Override
    public void updateStatus(Long productId, Integer status) {
        LambdaUpdateWrapper<Product> updateWrapper = new LambdaUpdateWrapper<Product>()
                .set(Product::getStatus, status)
                .eq(Product::getId, productId);
        productMapper.update(null, updateWrapper);
    }

    /**
     * 批量删除产品
     * @param ids 产品ID列表
     */
    @DistributedLock(key = "'product:lock:global'")
    @Override
    public void deleteBatch(List<String> ids) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<Product>()
                .in(Product::getId, ids)
                .eq(Product::getStatus, 1);
        Long count = productMapper.selectCount(wrapper);
        if (count > 0) {
            throw new BizException(ErrorCode.STATUS_INVALID);
        }
        //TODO 检查是否存在库存记录
        productMapper.deleteByIds(ids);
    }

    /**
     * 更新产品
     * @param product 产品实体
     * @return 产品VO
     */
    @DistributedLock(key = "'product:lock:global'")
    @Override
    public ProductVo update(Product product) {
        product.setProductCode(null);
        if (product.getCategoryId() != null) {
            // 校验分类是否存在
            RLock categoryLock = redissonClient.getLock("product:category:global");
            boolean acquired;
            try {
                // 尝试获取分类锁
                acquired = categoryLock.tryLock(5, 10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // 如果线程在等待锁的过程中被中断，重新设置中断状态并抛出异常
                Thread.currentThread().interrupt();
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "操作被中断");
            }
            if (!acquired) {
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "分类正在被其他用户操作，请稍后再试");
            }
            try {
                ProductCategory category = productCategoryMapper.selectById(product.getCategoryId());
                if (category == null) {
                    throw new BizException(ErrorCode.DATA_NOT_FOUND);
                }
                if (category.getStatus() == 0) {
                    throw new BizException(ErrorCode.CATEGORY_DISABLED);
                }
            } finally {
                if (categoryLock.isHeldByCurrentThread()) {
                    categoryLock.unlock();
                }
            }
        }

        if (product.getSafetyStockQty() != null) {
            product.setSafetyStockQty(BigDecimal.valueOf(QtyUtil.toStored(product.getSafetyStockQty())));
        }
        productMapper.updateById(product);
        return getDetailById(product.getId());
    }

}