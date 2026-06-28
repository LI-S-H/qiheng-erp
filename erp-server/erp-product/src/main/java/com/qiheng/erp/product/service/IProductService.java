package com.qiheng.erp.product.service;

import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.product.domain.dto.ProductBatchStatusDto;
import com.qiheng.erp.product.domain.dto.ProductPageDto;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.product.domain.vo.ProductVo;
import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.validation.Valid;

import java.util.List;

/**
 * <p>
 * 产品表 服务类
 * </p>
 *
 * @author Li
 * @since 2026-06-28
 */
public interface IProductService extends IService<Product> {
    /**
     * 产品分页查询
     * @param dto 分页查询参数DTO
     * @return 分页查询结果VO
     */
    PageResult<ProductVo> page(ProductPageDto dto);

    /**
     * 产品新增
     * @param product 产品实体
     * @return 产品VO
     */
    ProductVo add(@Valid Product product);

    /**
     * 产品详情查询
     * @param id 产品ID
     * @return 产品VO
     */
    ProductVo getDetailById(Long id);

    /**
     * 批量更新产品状态
     * @param dto 批量更新产品状态参数DTO
     */
    void updateBatchStatus(@Valid ProductBatchStatusDto dto);

    /**
     * 更新产品状态
     * @param productId 产品ID
     * @param status 状态
     */
    void updateStatus(Long productId, Integer status);

    /**
     * 更新产品
     * @param product 产品实体
     * @return 产品VO
     */
    ProductVo update(Product product);

    /**
     * 批量删除产品
     * @param ids 产品ID列表
     */
    void deleteBatch(List<String> ids);
}