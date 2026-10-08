package com.qiheng.erp.product.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.qiheng.erp.common.annotation.DistributedLock;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.scoring.ProductReferencePriceChangeHandler;
import com.qiheng.erp.common.util.CodeNoDefinition;
import com.qiheng.erp.common.util.CodeNoGenerator;
import com.qiheng.erp.common.util.IdUtil;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.product.domain.dto.ProductBatchStatusDto;
import com.qiheng.erp.product.domain.dto.ProductPageDto;
import com.qiheng.erp.product.domain.dto.ProductReferencePriceDto;
import com.qiheng.erp.product.domain.dto.ProductSaveDto;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.product.domain.entity.ProductCategory;
import com.qiheng.erp.product.domain.vo.ProductVo;
import com.qiheng.erp.product.mapper.ProductCategoryMapper;
import com.qiheng.erp.product.mapper.ProductMapper;
import com.qiheng.erp.product.service.IProductService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 * 产品表 服务实现类
 * </p>
 *
 * @author Li
 * @since 2026-06-28
 */
@Slf4j
@Service
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements IProductService {

    private static final CodeNoDefinition PRODUCT_CODE = new CodeNoDefinition("product:code", "P", 6);

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ProductCategoryMapper productCategoryMapper;

    @Autowired
    private CodeNoGenerator codeNoGenerator;

    /**
     * 产品参考采购价变化处理器(由 Spring 注入所有实现)。
     * erp-purchase 提供实现,本模块调用即可,不需要直接依赖 erp-purchase。
     * 允许为空(单元测试或 erp-purchase 未加载场景),空时跳过评分重算。
     */
    @Autowired(required = false)
    private List<ProductReferencePriceChangeHandler> referencePriceHandlers;

    /**
     * 产品分页查询
     * @param dto 分页查询参数DTO
     * @return 分页查询结果VO
     */
    @Override
    public PageResult<ProductVo> page(ProductPageDto dto) {
        // 如果指定了分类ID，查询该分类及所有子分类的ID
        Set<Long> categoryIds = null;
        if (dto.getCategoryId() != null) {
            categoryIds = new HashSet<>();
            collectChildCategoryIds(dto.getCategoryId(), categoryIds);
        }

        MPJLambdaWrapper<Product> wrapper = new MPJLambdaWrapper<Product>()
                .selectAs(Product::getId, ProductVo::getProductId)
                .select(Product::getProductCode)
                .select(Product::getProductName)
                .select(Product::getCategoryId)
                .selectAs(ProductCategory::getCategoryName, ProductVo::getCategoryName)
                .select(Product::getBrandName)
                .select(Product::getUnitName)
                .select(Product::getQuantityPrecision)
                .select(Product::getSpecification)
                .select(Product::getBarcode)
                .select(Product::getReferencePurchasePrice)
                .select(Product::getReferenceSalePrice)
                .select(Product::getSafetyStockQty)
                .select(Product::getStatus)
                .select(Product::getRemark)
                .select(Product::getCreateTime)
                .select(Product::getUpdateTime)
                .leftJoin(ProductCategory.class, ProductCategory::getId, Product::getCategoryId)
                .like(StrUtil.isNotBlank(dto.getProductCode()), Product::getProductCode, dto.getProductCode())
                .like(StrUtil.isNotBlank(dto.getProductName()), Product::getProductName, dto.getProductName())
                .in(categoryIds != null, Product::getCategoryId, categoryIds)
                .like(StrUtil.isNotBlank(dto.getBrandName()), Product::getBrandName, dto.getBrandName())
                .like(StrUtil.isNotBlank(dto.getBarcode()), Product::getBarcode, dto.getBarcode())
                .eq(dto.getStatus() != null, Product::getStatus, dto.getStatus())
                .orderByDesc(Product::getCreateTime);
        Page<ProductVo> result = productMapper.selectJoinPage(dto.toPage(), ProductVo.class, wrapper);
        result.getRecords().forEach(vo -> {
            if (vo.getSafetyStockQty() != null) {
                vo.setSafetyStockQty(QtyUtil.toDecimal(vo.getSafetyStockQty()));
            }
        });
        return PageResult.of(result.getRecords(), (int) result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    private void collectChildCategoryIds(Long parentId, Set<Long> categoryIds) {
        // 一次性查出所有启用分类
        List<ProductCategory> allCategories = productCategoryMapper.selectList(new LambdaQueryWrapper<>());
        // 构建 parentId -> List<childId> 映射表
        Map<Long, List<Long>> parentChildMap = allCategories.stream()
                .collect(Collectors.groupingBy(ProductCategory::getParentId,
                        Collectors.mapping(ProductCategory::getId, Collectors.toList())));
        categoryIds.add(parentId);
        collectChildIdsFromMap(parentId, parentChildMap, categoryIds);
    }

    /**
     * 递归查询所有子分类ID
     * @param parentId 父分类ID
     * @param parentChildMap parentId -> List<childId> 映射表
     * @param result 子分类ID集合
     */
    private void collectChildIdsFromMap(Long parentId, Map<Long, List<Long>> parentChildMap, Set<Long> result) {
        List<Long> children = parentChildMap.get(parentId);
        if (children != null) {
            for (Long childId : children) {
                result.add(childId);
                collectChildIdsFromMap(childId, parentChildMap, result);
            }
        }
    }

    /**
     * 产品新增
     * @param dto 产品新增请求
     * @return 产品VO
     */
    @Override
    @DistributedLock(key ="'product:category:global'",waitTime = 5,leaseTime = 10,timeUnit = TimeUnit.SECONDS)
    public ProductVo add(ProductSaveDto dto) {
        Product product = toEntity(dto, null);
        product.setProductCode(codeNoGenerator.nextNo(PRODUCT_CODE, () -> productMapper.findMaxProductCodeSequence(
                PRODUCT_CODE.prefix(), PRODUCT_CODE.prefix().length(), PRODUCT_CODE.width())));
        // 校验分类是否存在且状态正常
        ProductCategory category = productCategoryMapper.selectById(product.getCategoryId());
        if (category == null || category.getStatus() != 1) {
            throw new BizException(ErrorCode.CATEGORY_ERROR);
        }
        productMapper.insert(product);
        return getDetailById(product.getId());
    }

    /**
     * 请求转实体：ID 类字段按字符串解析，安全库存按 100 倍整数落库。
     */
    private Product toEntity(ProductSaveDto dto, Long productId) {
        validateSafetyStockPrecision(dto);
        Product product = new Product();
        product.setId(productId);
        product.setProductName(dto.getProductName());
        product.setCategoryId(IdUtil.parseOptionalLongId(dto.getCategoryId(), "产品分类ID"));
        product.setBrandName(dto.getBrandName());
        product.setUnitName(dto.getUnitName());
        product.setQuantityPrecision(dto.getQuantityPrecision());
        product.setSpecification(dto.getSpecification());
        product.setBarcode(dto.getBarcode());
        product.setReferencePurchasePrice(dto.getReferencePurchasePrice());
        product.setReferenceSalePrice(dto.getReferenceSalePrice());
        product.setSafetyStockQty(QtyUtil.toStored(dto.getSafetyStockQty()));
        product.setStatus(dto.getStatus());
        product.setRemark(dto.getRemark());
        return product;
    }

    /**
     * 安全库存的小数位不得超过同一请求声明的数量精度，避免落库时被静默四舍五入。
     */
    private void validateSafetyStockPrecision(ProductSaveDto dto) {
        BigDecimal safetyStockQty = dto.getSafetyStockQty();
        if (safetyStockQty == null || dto.getQuantityPrecision() == null) {
            return;
        }
        if (safetyStockQty.stripTrailingZeros().scale() > dto.getQuantityPrecision()) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(),
                    "安全库存数量最多保留 " + dto.getQuantityPrecision() + " 位小数");
        }
    }

    /**
     * 根据ID查询产品详情
     * @param id 产品ID
     * @return 产品VO
     */
    @Override
    public ProductVo getDetailById(Long id) {
        MPJLambdaWrapper<Product> wrapper = new MPJLambdaWrapper<Product>()
                .selectAs(Product::getId, ProductVo::getProductId)
                .select(Product::getProductCode)
                .select(Product::getProductName)
                .select(Product::getCategoryId)
                .selectAs(ProductCategory::getCategoryName, ProductVo::getCategoryName)
                .select(Product::getBrandName)
                .select(Product::getUnitName)
                .select(Product::getQuantityPrecision)
                .select(Product::getSpecification)
                .select(Product::getBarcode)
                .select(Product::getReferencePurchasePrice)
                .select(Product::getReferenceSalePrice)
                .select(Product::getSafetyStockQty)
                .select(Product::getStatus)
                .select(Product::getRemark)
                .select(Product::getCreateTime)
                .select(Product::getUpdateTime)
                .leftJoin(ProductCategory.class, ProductCategory::getId, Product::getCategoryId)
                .eq(Product::getId, id);
        ProductVo vo = productMapper.selectJoinOne(ProductVo.class, wrapper);
        if (vo != null && vo.getSafetyStockQty() != null) {
            vo.setSafetyStockQty(QtyUtil.toDecimal(vo.getSafetyStockQty()));
        }
        return vo;
    }


    /**
     * 批量更新产品状态
     * @param dto 批量更新产品状态参数DTO
     */
    @DistributedLock(key = "'product:category:global'")
    @Override
    public void updateBatchStatus(ProductBatchStatusDto dto) {
        // 停用时校验：产品不能被未完成的业务单据引用（OR EXISTS 短路校验）
        if (Integer.valueOf(0).equals(dto.getStatus())) {
            Boolean hasUnfinishedRef = productMapper.existsUnfinishedBusinessReferencesByProductIds(
                    dto.getProductIds());
            if (Boolean.TRUE.equals(hasUnfinishedRef)) {
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                        "产品被未完成业务引用，无法停用");
            }
        }
        LambdaUpdateWrapper<Product> updateWrapper = new LambdaUpdateWrapper<Product>()
                .set(Product::getStatus, dto.getStatus())
                .in(Product::getId, dto.getProductIds());
        productMapper.update(null, updateWrapper);
    }

    /**
     * 更新产品状态
     * @param productId 产品ID
     * @param status 状态
     */
    @DistributedLock(key = "'product:category:global'")
    @Override
    public void updateStatus(Long productId, Integer status) {
        // 停用时校验：产品不能被未完成的业务单据引用（OR EXISTS 短路校验）
        if (Integer.valueOf(0).equals(status)) {
            Boolean hasUnfinishedRef = productMapper.existsUnfinishedBusinessReferencesByProductIds(
                    List.of(String.valueOf(productId)));
            if (Boolean.TRUE.equals(hasUnfinishedRef)) {
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                        "产品被未完成业务引用，无法停用");
            }
        }
        LambdaUpdateWrapper<Product> updateWrapper = new LambdaUpdateWrapper<Product>()
                .set(Product::getStatus, status)
                .eq(Product::getId, productId);
        productMapper.update(null, updateWrapper);
    }

    /**
     * 批量删除产品
     * @param ids 产品ID列表
     */
    @DistributedLock(key = "'product:category:global'")
    @Override
    public void deleteBatch(List<String> ids) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<Product>()
                .in(Product::getId, ids)
                .eq(Product::getStatus, 1);
        Long count = productMapper.selectCount(wrapper);
        if (count > 0) {
            throw new BizException(ErrorCode.STATUS_INVALID);
        }
        // 删除校验：产品不能被任何软删除外的业务记录引用（OR EXISTS 短路校验；已包含 supplier_product）
        Boolean hasActiveRef = productMapper.existsAnyActiveReferencesByProductIds(ids);
        if (Boolean.TRUE.equals(hasActiveRef)) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                    "产品被业务记录引用，无法删除");
        }
        productMapper.deleteByIds(ids);
    }

    /**
     * 更新产品
     * @param productId 产品ID
     * @param dto 产品更新请求
     * @return 产品VO
     */
    @DistributedLock(key = "'product:category:global'")
    @Override
    public ProductVo update(Long productId, ProductSaveDto dto) {
        // 产品编码由后端生成且创建后不可修改，转换时不写入该字段。
        Product product = toEntity(dto, productId);
        if (product.getCategoryId() != null) {
            // 产品与分类写操作共用同一把锁，校验分类状态后不会被并发分类停用穿透。
            ProductCategory category = productCategoryMapper.selectById(product.getCategoryId());
            if (category == null) {
                throw new BizException(ErrorCode.DATA_NOT_FOUND);
            }
            if (category.getStatus() == 0) {
                throw new BizException(ErrorCode.CATEGORY_DISABLED);
            }
        }
        productMapper.updateById(product);
        // 参考采购价通过独立接口维护，以隔离产品编辑与供应商评分重算事务。
        return getDetailById(product.getId());
    }

    /**
     * 调整产品参考采购价。
     *
     * <p>独立于产品编辑接口,避免编辑与价格变更混在同一事务内导致重算副作用。
     * 价格变化时通过反转接口同步触发所有供应该产品的有效供货关系的价格分、
     * 推荐分与对应供应商汇总分重算;重算失败则整体回滚。</p>
     *
     * @param productId 需要调整参考采购价的产品ID
     * @param dto 新参考采购价
     * @return 包含最新参考采购价的产品详情
     */
    // 参考价可能重算多个供应商；使用看门狗续约，避免等待行锁或批量计算超过30秒后互斥提前失效。
    @DistributedLock(key = "'product:category:global'", leaseTime = -1)
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ProductVo updateReferencePrice(Long productId, ProductReferencePriceDto dto) {
        if (dto.getReferencePurchasePrice() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "参考采购价不能为空");
        }
        // 后续评分会等待供应商行锁；最外层使用读已提交，避免等待后继续读取旧报价快照。
        Product existing = productMapper.selectById(productId);
        if (existing == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "产品不存在");
        }
        BigDecimal oldPrice = existing.getReferencePurchasePrice();
        BigDecimal newPrice = dto.getReferencePurchasePrice();

        // 产品表没有 version；更新由外层产品锁串行保护，不接收无效版本参数。
        Product update = new Product();
        update.setId(productId);
        update.setReferencePurchasePrice(newPrice);
        int rows = productMapper.updateById(update);
        if (rows == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                    "产品数据已被修改,请刷新后重试");
        }

        // 仅当价格实际变化时触发评分重算
        if (oldPrice == null || oldPrice.compareTo(newPrice) != 0) {
            log.info("产品参考采购价变化 productId={} oldPrice={} newPrice={}",
                    productId, oldPrice, newPrice);
            for (ProductReferencePriceChangeHandler handler : referencePriceHandlers) {
                try {
                    handler.onReferencePriceChanged(productId);
                } catch (Exception e) {
                    log.error("参考价变更评分重算失败 productId={}", productId, e);
                    throw e;
                }
            }
        } else {
            log.debug("产品参考采购价未变化,跳过评分重算 productId={}", productId);
        }

        return getDetailById(productId);
    }

}
