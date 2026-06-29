package com.qiheng.erp.product.service;

import com.qiheng.erp.product.domain.entity.ProductCategory;
import com.qiheng.erp.product.domain.vo.ProductCategoryListVo;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 * 产品分类表 服务类
 * </p>
 *
 * @author Li
 * @since 2026-06-28
 */
public interface IProductCategoryService extends IService<ProductCategory> {

    List<ProductCategoryListVo> listWithProductCount(String categoryName, Integer status);
}