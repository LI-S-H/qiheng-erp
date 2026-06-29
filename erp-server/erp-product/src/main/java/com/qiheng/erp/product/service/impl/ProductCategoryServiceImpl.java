package com.qiheng.erp.product.service.impl;

import cn.hutool.core.util.StrUtil;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.product.domain.entity.ProductCategory;
import com.qiheng.erp.product.domain.vo.ProductCategoryListVo;
import com.qiheng.erp.product.mapper.ProductCategoryMapper;
import com.qiheng.erp.product.service.IProductCategoryService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

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

    @Override
    public List<ProductCategoryListVo> listWithProductCount(String categoryName, Integer status) {
        MPJLambdaWrapper<ProductCategory> wrapper = new MPJLambdaWrapper<ProductCategory>()
                .selectAs(ProductCategory::getId, ProductCategoryListVo::getCategoryId)
                .selectAs(ProductCategory::getParentId, ProductCategoryListVo::getParentId)
                .select(ProductCategory::getCategoryName)
                .select(ProductCategory::getStatus)
                .selectCount(Product::getId, ProductCategoryListVo::getProductCount)
                .select(ProductCategory::getCreateTime)
                .select(ProductCategory::getUpdateTime)
                .leftJoin(Product.class, Product::getCategoryId, ProductCategory::getId,
                        ext -> ext.eq(Product::getDeleted, 0))
                .eq(ProductCategory::getDeleted, 0)
                .like(StrUtil.isNotBlank(categoryName), ProductCategory::getCategoryName, categoryName)
                .eq(status != null, ProductCategory::getStatus, status)
                .groupBy(ProductCategory::getId)
                .orderByDesc(ProductCategory::getCreateTime);
        return productCategoryMapper.selectJoinList(ProductCategoryListVo.class, wrapper);
    }
}