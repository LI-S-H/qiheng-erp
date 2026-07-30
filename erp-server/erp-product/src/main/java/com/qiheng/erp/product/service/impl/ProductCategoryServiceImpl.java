package com.qiheng.erp.product.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.qiheng.erp.common.annotation.DistributedLock;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.util.IdUtil;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.product.domain.entity.ProductCategory;
import com.qiheng.erp.product.domain.vo.ProductCategoryVo;
import com.qiheng.erp.product.mapper.ProductCategoryMapper;
import com.qiheng.erp.product.mapper.ProductMapper;
import com.qiheng.erp.product.service.IProductCategoryService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 * 产品分类表 服务实现类
 * </p>
 *
 * @author Li
 * @since 2026-06-28
 */
@Service
public class ProductCategoryServiceImpl extends ServiceImpl<ProductCategoryMapper, ProductCategory> implements IProductCategoryService {

    @Autowired
    private ProductCategoryMapper productCategoryMapper;

    @Autowired
    private ProductMapper productMapper;

    /**
     * 查询产品分类列表，包含产品数量
     * @param categoryName 分类名称
     * @param status 状态：1启用，0禁用
     * @return 产品分类列表，包含产品数量
     */
    @Override
    public List<ProductCategoryVo> listWithProductCount(String categoryName, Integer status) {
        MPJLambdaWrapper<ProductCategory> wrapper = new MPJLambdaWrapper<ProductCategory>()
                .selectAs(ProductCategory::getId, ProductCategoryVo::getCategoryId)
                .selectAs(ProductCategory::getParentId, ProductCategoryVo::getParentId)
                .select(ProductCategory::getCategoryName)
                .select(ProductCategory::getStatus)
                .selectCount(Product::getId, ProductCategoryVo::getProductCount)
                .select(ProductCategory::getCreateTime)
                .select(ProductCategory::getUpdateTime)
                .leftJoin(Product.class, Product::getCategoryId, ProductCategory::getId)
                .like(StrUtil.isNotBlank(categoryName), ProductCategory::getCategoryName, categoryName)
                .eq(status != null, ProductCategory::getStatus, status)
                .groupBy(ProductCategory::getId)
                .orderByDesc(ProductCategory::getCreateTime);
        return productCategoryMapper.selectJoinList(ProductCategoryVo.class, wrapper);
    }

    /**
     * 根据分类ID查询产品分类详情
     * @param categoryId 分类ID
     * @return 产品分类详情
     */
    @Override
    public ProductCategoryVo getDetailById(Long categoryId) {
        MPJLambdaWrapper<ProductCategory> wrapper = new MPJLambdaWrapper<ProductCategory>()
                .selectAs(ProductCategory::getId, ProductCategoryVo::getCategoryId)
                .selectAs(ProductCategory::getParentId, ProductCategoryVo::getParentId)
                .select(ProductCategory::getCategoryName)
                .select(ProductCategory::getStatus)
                .selectCount(Product::getId, ProductCategoryVo::getProductCount)
                .select(ProductCategory::getCreateTime)
                .select(ProductCategory::getUpdateTime)
                .leftJoin(Product.class, Product::getCategoryId, ProductCategory::getId)
                .eq(ProductCategory::getId, categoryId)
                .groupBy(ProductCategory::getId);
        return productCategoryMapper.selectJoinOne(ProductCategoryVo.class, wrapper);
    }

    /**
     * 批量更新产品分类状态
     * @param categoryIds 分类ID列表
     * @param status 状态：1启用，0禁用
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @DistributedLock(key = "'product:category:global'")
    public void updateBatchStatus(List<String> categoryIds, Integer status) {
        List<Long> ids = IdUtil.parseRequiredLongIds(categoryIds, "产品分类ID");
        if (status == 0) {
            disableCategories(ids);
        } else {
            enableCategories(ids);
        }
    }

    /**
     * 禁用产品分类
     * @param categoryIds 分类ID列表
     */
    private void disableCategories(List<Long> categoryIds) {
        // 获取所有产品分类
        List<ProductCategory> allCategories = list();
        // 构建分类ID到子分类列表的映射
        Map<Long, List<ProductCategory>> childrenMap = allCategories.stream()
                .collect(Collectors.groupingBy(ProductCategory::getParentId));
        // 构建所有要禁用的分类ID集合
        Set<Long> allIds = new HashSet<>(categoryIds);
        // 递归收集所有分类的后代分类ID
        for (Long categoryId : categoryIds) {
            collectDescendants(childrenMap, categoryId, allIds);
        }
        // 禁用所有分类
        LambdaUpdateWrapper<ProductCategory> categoryUpdate = new LambdaUpdateWrapper<ProductCategory>()
                .set(ProductCategory::getStatus, 0)
                .in(ProductCategory::getId, allIds);
        productCategoryMapper.update(null, categoryUpdate);
        // 禁用所有分类下的产品
        LambdaUpdateWrapper<Product> productUpdate = new LambdaUpdateWrapper<Product>()
                .set(Product::getStatus, 0)
                .in(Product::getCategoryId, allIds);
        productMapper.update(null, productUpdate);
    }

    /**
     * 递归收集所有分类的后代分类ID
     * @param childrenMap 分类ID到子分类列表的映射
     * @param parentId 父分类ID
     * @param result 存储所有后代分类ID的集合
     */
    private void collectDescendants(Map<Long, List<ProductCategory>> childrenMap, Long parentId, Set<Long> result) {
        List<ProductCategory> children = childrenMap.get(parentId);
        if (children == null || children.isEmpty()) {
            return;
        }
        for (ProductCategory child : children) {
            // 将子分类ID添加到结果集合中
            result.add(child.getId());
            // 递归收集子分类的后代分类ID
            collectDescendants(childrenMap, child.getId(), result);
        }
    }

    /**
     * 启用产品分类
     * @param categoryIds 分类ID列表
     */
    private void enableCategories(List<Long> categoryIds) {
        // 获取所有产品分类
        List<ProductCategory> allCategories = list();
        // 构建分类ID到父分类ID的映射
        Map<Long, Long> childToParent = allCategories.stream()
                .collect(Collectors.toMap(ProductCategory::getId, ProductCategory::getParentId));
        // 构建所有要启用的分类ID集合
        Set<Long> requestSet = new HashSet<>(categoryIds);
        // 遍历所有要启用的分类ID, 验证其分类的所有父分类是否已启用
        for (Long categoryId : categoryIds) {
            validateAncestorsEnabled(allCategories, categoryId, requestSet, childToParent);
        }
        // 启用所有分类
        LambdaUpdateWrapper<ProductCategory> categoryUpdate = new LambdaUpdateWrapper<ProductCategory>()
                .set(ProductCategory::getStatus, 1)
                .in(ProductCategory::getId, categoryIds);
        productCategoryMapper.update(null, categoryUpdate);
    }

    /**
     * 验证分类的所有父分类是否已启用
     * @param allCategories 所有产品分类
     * @param categoryId 分类ID
     * @param requestSet 请求分类ID集合
     * @param childToParent 分类ID到父分类ID的映射
     */
    private void validateAncestorsEnabled(List<ProductCategory> allCategories, Long categoryId,
                                           Set<Long> requestSet, Map<Long, Long> childToParent) {
        // 从当前分类开始向上遍历，直到到达根分类或找到已启用的父分类
        Long current = childToParent.get(categoryId);
        while (current != null && current != 0L) {
            if (requestSet.contains(current)) {
                // 如果当前父分类在请求集合中，继续向上遍历
                current = childToParent.get(current);
                continue;
            }
            // 如果当前父分类不在请求集合中，检查其状态是否已启用
            Long finalCurrent = current;
            // 查找当前父分类
            ProductCategory parent = allCategories.stream()
                    .filter(c -> c.getId().equals(finalCurrent))
                    .findFirst().orElse(null);
            // 如果当前父分类不存在或状态为禁用，抛出异常
            if (parent == null || parent.getStatus() == 0) {
                throw new BizException(ErrorCode.PARENT_DEPT_DISABLED);
            }
            // 如果当前父分类状态为启用，继续向上遍历
            current = childToParent.get(current);
        }
    }

    /**
     * 批量删除产品分类
     * 删除前校验：存在子分类或有关联产品时返回 409
     * @param ids 分类ID列表
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @DistributedLock(key ="'product:category:global'",waitTime = 5,leaseTime = 10,timeUnit = TimeUnit.SECONDS)
    public void removeBatch(List<Long> ids) {
        // 校验是否存在子分类
        Long childCount = productCategoryMapper.selectCount(
                new LambdaQueryWrapper<ProductCategory>()
                        .in(ProductCategory::getParentId, ids));
        if (childCount > 0) {
            throw new BizException(ErrorCode.CATEGORY_HAS_CHILDREN);
        }
        // 校验是否存在有关联产品
        Long productCount = productMapper.selectCount(
                new LambdaQueryWrapper<Product>()
                        .in(Product::getCategoryId, ids));
        if (productCount > 0) {
            throw new BizException(ErrorCode.CATEGORY_HAS_PRODUCTS);
        }
        removeByIds(ids);
    }

    /**
     * 更新产品分类
     * @param productCategory 产品分类实体
     * @return 更新后的产品分类VO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @DistributedLock(key ="'product:category:global'")
    public ProductCategoryVo update(ProductCategory productCategory) {
        //查询当前分类
        ProductCategory oldCategory = getById(productCategory.getId());
        // 校验是否存在
        if (oldCategory == null) {
            throw new BizException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        // 判断有没有更新父分类
        if (!oldCategory.getParentId().equals(productCategory.getParentId())) {
            // 自引用提前拦截
            if (productCategory.getId().equals(productCategory.getParentId())) {
                throw new BizException(ErrorCode.CATEGORY_CYCLE_REFERENCE);
            }
            // 移到根级不需要校验父分类
            if (productCategory.getParentId() != 0L) {
                ProductCategory parent = getById(productCategory.getParentId());
                if (parent == null) {
                    throw new BizException(ErrorCode.PARENT_NOT_FOUND);
                }
                if (parent.getStatus() == 0) {
                    throw new BizException(ErrorCode.PARENT_DISABLED);
                }
            }
            // 校验是否存在循环引用
            validateCycleReference(productCategory);
        }
        //校验状态
        if (productCategory.getStatus() == 0) {
            // 禁用分类时，禁用所有子分类和产品
            disableCategories(List.of(productCategory.getId()));
        } else {
            // 启用分类时，判断所有父分类是否已启用
            enableCategories(List.of(productCategory.getId()));
        }
        updateById(productCategory);
        return getDetailById(productCategory.getId());
    }

    /**
     * 校验是否存在循环引用
     * @param productCategory 产品分类实体
     */
    private void validateCycleReference(ProductCategory productCategory) {
        // 获取所有分类
        List<ProductCategory> allCategories = list();
        // 构建子分类映射
        Map<Long, List<ProductCategory>> childrenMap = allCategories.stream()
                .collect(Collectors.groupingBy(ProductCategory::getParentId));
        // 构建所有子分类的id集合
        Set<Long> descendants = new HashSet<>();
        // 递归获取所有子分类的id
        collectDescendants(childrenMap, productCategory.getId(), descendants);
        if (descendants.contains(productCategory.getParentId())) {
            throw new BizException(ErrorCode.CATEGORY_CYCLE_REFERENCE);
        }
    }


}
