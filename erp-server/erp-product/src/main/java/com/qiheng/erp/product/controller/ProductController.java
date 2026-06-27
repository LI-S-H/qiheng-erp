package com.qiheng.erp.product.controller;


import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.product.domain.dto.ProductPageDto;
import com.qiheng.erp.product.domain.vo.ProductVo;
import com.qiheng.erp.product.service.IProductService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 产品表 前端控制器
 * </p>
 *
 * @author Li
 * @since 2026-06-28
 */
@RestController
@RequestMapping("/products")
@Slf4j
public class ProductController {
    @Autowired
    private IProductService productService;

    /**
     * 产品分页查询
     * @param dto 分页查询参数DTO
     * @return 分页查询结果VO
     */
    @GetMapping
    @Operation(summary = "产品分页查询")
    public Result<PageResult<ProductVo>> page(@Valid ProductPageDto dto) {
        StpUtil.checkPermission("product:query");
        log.info("产品分页查询，参数: {}", dto);
        PageResult<ProductVo> page = productService.page(dto);
        return Result.ok(page);
    }


}
