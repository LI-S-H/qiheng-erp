package com.qiheng.erp.product.controller;

import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.product.domain.vo.ProductCategoryListVo;
import com.qiheng.erp.product.service.IProductCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <p>
 * 产品分类表 前端控制器
 * </p>
 *
 * @author Li
 * @since 2026-06-28
 */
@RestController
@RequestMapping("/product/categories")
@Slf4j
@Tag(name = "产品分类管理")
public class ProductCategoryController {

    @Autowired
    private IProductCategoryService productCategoryService;

    /**
     * 查询产品分类列表
     *
     */
    @Operation(summary = "查询产品分类列表")
    @GetMapping
    public Result<List<ProductCategoryListVo>> list(
            @RequestParam(required = false) String categoryName,
            @RequestParam(required = false) Integer status) {
        log.info("查询产品分类列表，categoryName: {}, status: {}", categoryName, status);
        List<ProductCategoryListVo> list = productCategoryService.listWithProductCount(categoryName, status);
        return Result.ok(list);
    }
}