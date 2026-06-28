package com.qiheng.erp.product.controller;


import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.product.domain.dto.ProductBatchStatusDto;
import com.qiheng.erp.product.domain.dto.ProductPageDto;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.product.domain.vo.ProductVo;
import com.qiheng.erp.product.service.IProductService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    /**
     * 产品新增
     * @param product 产品实体
     * @return 产品VO
     */
    @PostMapping
    @Operation(summary = "产品新增")
    public Result<ProductVo> save(@Valid @RequestBody Product product) {
        StpUtil.checkPermission("product:manage");
        log.info("产品新增，参数: {}", product);
        ProductVo vo = productService.add(product);
        return Result.ok(vo);

    }

    /**
     * 根据ID查询产品详情
     * @param productId 产品ID
     * @return 产品VO
     */
    @GetMapping("/{productId}")
    @Operation(summary = "根据ID查询产品详情")
    public Result<ProductVo> getDetailById(@PathVariable Long productId) {
        StpUtil.checkPermission("product:query");
        log.info("根据ID查询产品详情，参数: {}", productId);
        ProductVo vo = productService.getDetailById(productId);
        return Result.ok(vo);
    }

    /**
     * 批量更新产品状态
     * @param dto 批量更新产品状态参数DTO
     * @return 无
     */
    @PatchMapping("/batch/status")
    @Operation(summary = "批量更新产品状态")
    public Result<Void> updateBatchStatus(@Valid @RequestBody ProductBatchStatusDto dto) {
        StpUtil.checkPermission("product:manage");
        log.info("批量更新产品状态，参数: {}", dto);
        productService.updateBatchStatus(dto);
        return Result.ok();
    }

    /**
     * 更新产品状态
     * @param productId 产品ID
     * @param status 状态
     * @return 无
     */
    @PatchMapping("/{productId}/status")
    @Operation(summary = "更新产品状态")
    public Result<Void> updateStatus(@PathVariable Long productId, @RequestBody Map<String, Integer> status) {
        StpUtil.checkPermission("product:manage");
        Integer s = status.get("status");
        log.info("更新产品状态，参数: {}, {}", productId, s);
        productService.updateStatus(productId, s);
        return Result.ok();
    }

    /**
     * 批量删除产品
     * @param productIds 产品ID列表参数DTO
     * @return 无
     */
    @PostMapping("/batch/delete")
    @Operation(summary = "批量删除产品")
    public Result<Void> deleteBatch(@RequestBody Map<String, List<String>> productIds) {
        StpUtil.checkPermission("product:manage");
        List<String> ids = productIds.get("productIds");
        log.info("批量删除产品，参数: {}", ids);
        productService.deleteBatch(ids);
        return Result.ok();
    }

    /**
     * 删除产品
     * @param productId 产品ID
     * @return 无
     */
    @DeleteMapping("/{productId}")
    @Operation(summary = "删除产品")
    public Result<Void> deleteById(@PathVariable Long productId) {
        StpUtil.checkPermission("product:manage");
        log.info("删除产品，参数: {}", productId);
        productService.deleteBatch(List.of(String.valueOf(productId)));
        return Result.ok();
    }

    /**
     * 更新产品
     * @param productId 产品ID
     * @param product 产品实体
     * @return 产品VO
     */
    @PutMapping("/{productId}")
    @Operation(summary = "更新产品")
    public Result<ProductVo> update(@PathVariable("productId") Long productId, @RequestBody Product product) {
        StpUtil.checkPermission("product:manage");
        log.info("更新产品，参数: {}, {}", productId, product);
        product.setId(productId);
        ProductVo vo = productService.update(product);
        return Result.ok(vo);
    }
}