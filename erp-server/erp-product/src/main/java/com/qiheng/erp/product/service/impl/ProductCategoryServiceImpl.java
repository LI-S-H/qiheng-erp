package com.qiheng.erp.product.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.qiheng.erp.common.annotation.DistributedLock;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
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
        List<Long> ids = categoryIds.stream().map(Long::valueOf).toList();
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


}