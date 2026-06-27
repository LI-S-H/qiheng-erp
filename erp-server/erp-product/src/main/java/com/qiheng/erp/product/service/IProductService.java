package com.qiheng.erp.product.service;

import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.product.domain.dto.ProductPageDto;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.product.domain.vo.ProductVo;
import com.baomidou.mybatisplus.extension.service.IService;

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
}