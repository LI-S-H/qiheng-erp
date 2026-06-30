package com.qiheng.erp.product.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.product.domain.dto.CategoryBatchUpdateDto;
import com.qiheng.erp.product.domain.entity.ProductCategory;
import com.qiheng.erp.product.domain.vo.ProductCategoryVo;
import com.qiheng.erp.product.service.IProductCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

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
    public Result<List<ProductCategoryVo>> list(
            @RequestParam(required = false) String categoryName,
            @RequestParam(required = false) Integer status) {
        StpUtil.checkPermission("product:query");
        log.info("查询产品分类列表，categoryName: {}, status: {}", categoryName, status);
        List<ProductCategoryVo> list = productCategoryService.listWithProductCount(categoryName, status);
        return Result.ok(list);
    }

    /**
     * 查询产品分类详情
     *
     */
    @Operation(summary = "查询产品分类详情")
    @GetMapping("/{categoryId}")
    public Result<ProductCategoryVo> detail(@PathVariable Long categoryId) {
        StpUtil.checkPermission("product:query");
        log.info("查询产品分类详情，categoryId: {}", categoryId);
        ProductCategoryVo productCategoryVo = productCategoryService.getDetailById(categoryId);
        return Result.ok(productCategoryVo);
    }

    /**
     * 创建产品分类
     *
     */
    @Operation(summary = "创建产品分类")
    @PostMapping
    public Result<ProductCategoryVo> create(@Valid @RequestBody ProductCategory productCategory) {
        StpUtil.checkPermission("product:manage");
        log.info("创建产品分类，productCategory: {}", productCategory);
        productCategoryService.save(productCategory);
        ProductCategoryVo productCategoryVo = productCategoryService.getDetailById(productCategory.getId());
        return Result.ok(productCategoryVo);
    }

    /**
     * 批量更新产品分类状态
     *
     */
    @Operation(summary = "批量更新产品分类状态")
    @PatchMapping("/batch/status")
    public Result<Void> updateStatus(@RequestBody @Valid CategoryBatchUpdateDto categoryBatchUpdateDto) {
        StpUtil.checkPermission("product:manage");
        log.info("批量更新产品分类状态，categoryBatchUpdateDto: {}", categoryBatchUpdateDto);
        productCategoryService.updateBatchStatus(categoryBatchUpdateDto.getCategoryIds(), categoryBatchUpdateDto.getStatus());
        return Result.ok();
    }

    /**
     * 更新产品分类状态
     * @param categoryId 分类ID
     * @param status 状态：1启用，0禁用
     * @return 无
     */
    @Operation(summary = "更新产品分类状态")
    @PatchMapping("/{categoryId}/status")
    public Result<Void> updateStatus(@PathVariable Long categoryId,
                                     @RequestBody Map<String, Integer> status)
    {
        StpUtil.checkPermission("product:manage");
        Integer statusValue = status.get("status");
        if (statusValue == null) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        log.info("更新产品分类状态，categoryId: {}, status: {}", categoryId, statusValue);
        productCategoryService.updateBatchStatus(List.of(categoryId.toString()), statusValue);
        return Result.ok();
    }

    /**
     * 批量删除产品分类
     * @param categoryIds 分类ID列表
     * @return 无
     */
    @Operation(summary = "批量删除产品分类")
    @PostMapping("/batch/delete")
    public Result<Void> deleteBatch(@RequestBody Map<String, List<Long>> categoryIds) {
        StpUtil.checkPermission("product:manage");
        List<Long> ids = categoryIds.get("ids");
        log.info("批量删除产品分类，categoryIds: {}", ids);
        productCategoryService.removeBatch(ids);
        return Result.ok();
    }

    /**
     * 删除产品分类
     * @param categoryId 分类ID
     * @return 无
     */
    @Operation(summary = "删除产品分类")
    @DeleteMapping("/{categoryId}")
    public Result<Void> deleteById(@PathVariable Long categoryId) {
        StpUtil.checkPermission("product:manage");
        log.info("删除产品分类，categoryId: {}", categoryId);
        productCategoryService.removeBatch(Collections.singletonList(categoryId));
        return Result.ok();
    }

    /**
     * 更新产品分类
     * @param categoryId 分类ID
     * @param productCategory 产品分类
     * @return 产品分类详情
     */
    @Operation(summary = "更新产品分类")
    @PutMapping("/{categoryId}")
    public Result<ProductCategoryVo> updateById(@PathVariable Long categoryId,
                                                @Valid @RequestBody ProductCategory productCategory)
    {
        StpUtil.checkPermission("product:manage");
        log.info("更新产品分类，categoryId: {}, productCategory: {}", categoryId, productCategory);
        productCategory.setId(categoryId);
        ProductCategoryVo vo = productCategoryService.update(productCategory);
        return Result.ok(vo);
    }
}