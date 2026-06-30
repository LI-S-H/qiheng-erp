package com.qiheng.erp.product.service;

import com.qiheng.erp.product.domain.entity.ProductCategory;
import com.qiheng.erp.product.domain.vo.ProductCategoryVo;
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

    /**
     * 查询产品分类列表，包含产品数量
     * @param categoryName 分类名称
     * @param status 状态：1启用，0禁用
     * @return 产品分类列表，包含产品数量
     */
    List<ProductCategoryVo> listWithProductCount(String categoryName, Integer status);

    /**
     * 根据分类ID查询产品分类详情
     * @param categoryId 分类ID
     * @return 产品分类详情
     */
    ProductCategoryVo getDetailById(Long categoryId);

    /**
     * 批量更新产品分类状态
     * @param categoryIds 分类ID列表
     * @param status 状态：1启用，0禁用
     */
    void updateBatchStatus(List<String> categoryIds, Integer status);

    /**
     * 批量删除产品分类
     * @param ids 分类ID列表
     */
    void removeBatch(List<Long> ids);

    /**
     * 更新产品分类
     * @param productCategory 产品分类实体
     * @return 更新后的产品分类VO
     */
    ProductCategoryVo update(ProductCategory productCategory);
}