package com.qiheng.erp.purchase.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.util.IdUtil;
import com.qiheng.erp.common.util.ParamValidator;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.product.mapper.ProductMapper;
import com.qiheng.erp.purchase.domain.supplierproduct.dto.SupplierProductBatchDeleteDto;
import com.qiheng.erp.purchase.domain.supplierproduct.dto.SupplierProductBatchStatusDto;
import com.qiheng.erp.purchase.domain.supplierproduct.dto.SupplierProductCreateDto;
import com.qiheng.erp.purchase.domain.supplierproduct.dto.SupplierProductPageDto;
import com.qiheng.erp.purchase.domain.supplierproduct.dto.SupplierProductQuoteDto;
import com.qiheng.erp.purchase.domain.supplierproduct.dto.SupplierProductUpdateDto;
import com.qiheng.erp.purchase.domain.supplier.entity.Supplier;
import com.qiheng.erp.purchase.domain.supplierproduct.entity.SupplierProduct;
import com.qiheng.erp.purchase.domain.supplierproduct.enums.QuoteStatus;
import com.qiheng.erp.purchase.domain.supplierproduct.enums.SupplierScoreStatus;
import com.qiheng.erp.purchase.domain.supplierproduct.vo.SupplierProductVo;
import com.qiheng.erp.purchase.mapper.SupplierMapper;
import com.qiheng.erp.purchase.mapper.SupplierProductMapper;
import com.qiheng.erp.purchase.service.ISupplierProductService;
import com.qiheng.erp.purchase.service.SupplierScoreRecalculateService;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;

/**
 * <p>
 * 供应商供货产品表 服务实现类
 * </p>
 *
 * @author Li
 * @since 2026-07-29
 */
@Service
public class SupplierProductServiceImpl extends ServiceImpl<SupplierProductMapper, SupplierProduct> implements ISupplierProductService {
    @Autowired
    private SupplierProductMapper supplierProductMapper;
    @Autowired
    private SupplierMapper supplierMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private SupplierScoreRecalculateService supplierScoreRecalculateService;

    /**
     * 供货产品分页查询（关联供应商、产品主数据获取展示字段）
     * @param dto 分页查询参数DTO
     * @return 分页查询结果VO
     */
    @Override
    public PageResult<SupplierProductVo> page(SupplierProductPageDto dto) {
        validatePageQuery(dto);
        Long supplierId = IdUtil.parseOptionalLongId(dto.getSupplierId(), "供应商ID");
        Long productId = IdUtil.parseOptionalLongId(dto.getProductId(), "产品ID");
        MPJLambdaWrapper<SupplierProduct> wrapper = buildBaseWrapper()
                .eq(supplierId != null, SupplierProduct::getSupplierId, supplierId)
                .eq(productId != null, SupplierProduct::getProductId, productId)
                .like(StrUtil.isNotBlank(dto.getSupplierName()), Supplier::getSupplierName, dto.getSupplierName())
                .like(StrUtil.isNotBlank(dto.getProductCode()), Product::getProductCode, dto.getProductCode())
                .like(StrUtil.isNotBlank(dto.getProductName()), Product::getProductName, dto.getProductName())
                .eq(dto.getStatus() != null, SupplierProduct::getStatus, dto.getStatus())
                .eq(StrUtil.isNotBlank(dto.getScoreStatus()), SupplierProduct::getScoreStatus, dto.getScoreStatus())
                .le(dto.getQuoteValidUntilEnd() != null, SupplierProduct::getQuoteValidUntil, dto.getQuoteValidUntilEnd())
                .ge(dto.getQualityScoreMin() != null, SupplierProduct::getQualityScore, QtyUtil.toStoredInt(dto.getQualityScoreMin()))
                .le(dto.getQualityScoreMax() != null, SupplierProduct::getQualityScore, QtyUtil.toStoredInt(dto.getQualityScoreMax()))
                .ge(dto.getPriceScoreMin() != null, SupplierProduct::getPriceScore, QtyUtil.toStoredInt(dto.getPriceScoreMin()))
                .le(dto.getPriceScoreMax() != null, SupplierProduct::getPriceScore, QtyUtil.toStoredInt(dto.getPriceScoreMax()))
                .ge(dto.getAiScoreMin() != null, SupplierProduct::getRecommendScore, QtyUtil.toStoredInt(dto.getAiScoreMin()))
                .le(dto.getAiScoreMax() != null, SupplierProduct::getRecommendScore, QtyUtil.toStoredInt(dto.getAiScoreMax()))
                .ge(dto.getScoreBasisAmountMin() != null, SupplierProduct::getScoreBasisAmount, QtyUtil.toStored(dto.getScoreBasisAmountMin()))
                .le(dto.getScoreBasisAmountMax() != null, SupplierProduct::getScoreBasisAmount, QtyUtil.toStored(dto.getScoreBasisAmountMax()))
                .ge(dto.getMinOrderQtyMin() != null, SupplierProduct::getMinOrderQty, QtyUtil.toStored(dto.getMinOrderQtyMin()))
                .le(dto.getMinOrderQtyMax() != null, SupplierProduct::getMinOrderQty, QtyUtil.toStored(dto.getMinOrderQtyMax()));
        applyQuoteStatus(wrapper, dto.getQuoteStatus());
        wrapper.orderByDesc(SupplierProduct::getCreateTime)
                .orderByDesc(SupplierProduct::getId);
        Page<SupplierProductVo> result = supplierProductMapper.selectJoinPage(dto.toPage(), SupplierProductVo.class, wrapper);
        result.getRecords().forEach(this::convertStoredValues);
        return PageResult.of(
                result.getRecords(),
                (int) result.getTotal(),
                (int) result.getCurrent(),
                (int) result.getSize()
        );
    }

    /**
     * 查询供货产品详情
     * @param supplierProductId 供货产品ID
     * @return 供货产品VO
     */
    @Override
    public SupplierProductVo detail(Long supplierProductId) {
        SupplierProductVo vo = toVo(supplierProductId);
        if (vo == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
        return vo;
    }

    /**
     * 应用报价状态条件
     */
    private void applyQuoteStatus(MPJLambdaWrapper<SupplierProduct> wrapper, String quoteStatus) {
        if (QuoteStatus.NONE.name().equals(quoteStatus)) {
            wrapper.isNull(SupplierProduct::getQuotedPurchasePrice);
        } else if (QuoteStatus.VALID.name().equals(quoteStatus)) {
            wrapper.isNotNull(SupplierProduct::getQuotedPurchasePrice)
                    .ge(SupplierProduct::getQuoteValidUntil, LocalDate.now());
        } else if (QuoteStatus.EXPIRED.name().equals(quoteStatus)) {
            wrapper.isNotNull(SupplierProduct::getQuotedPurchasePrice)
                    .lt(SupplierProduct::getQuoteValidUntil, LocalDate.now());
        }
    }

    /**
     * 验证分页查询参数
     */
    private void validatePageQuery(SupplierProductPageDto dto) {
        // 1. 验证评分状态
        ParamValidator.validateEnum(dto.getScoreStatus(), "评分状态",
                SupplierScoreStatus.NOT_READY.name(), SupplierScoreStatus.READY.name());
        ParamValidator.validateEnum(dto.getQuoteStatus(), "报价状态",
                QuoteStatus.NONE.name(), QuoteStatus.VALID.name(), QuoteStatus.EXPIRED.name());
        // 2. 验证评分范围
        ParamValidator.validateScoreRange(dto.getQualityScoreMin(), dto.getQualityScoreMax(), "质量分");
        ParamValidator.validateScoreRange(dto.getPriceScoreMin(), dto.getPriceScoreMax(), "价格分");
        ParamValidator.validateScoreRange(dto.getAiScoreMin(), dto.getAiScoreMax(), "推荐分");
        ParamValidator.validateNonNegativeRange(dto.getScoreBasisAmountMin(), dto.getScoreBasisAmountMax(), "评分样本金额");
        ParamValidator.validateNonNegativeRange(dto.getMinOrderQtyMin(), dto.getMinOrderQtyMax(), "最小起订量");
    }

    /**
     * 创建供货产品
     * @param dto 创建供货产品请求DTO
     * @return 供货产品VO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SupplierProductVo create(SupplierProductCreateDto dto) {
        // 解析必填 ID
        Long supplierId = IdUtil.parseRequiredLongId(dto.getSupplierId(), "供应商ID");
        Long productId = IdUtil.parseRequiredLongId(dto.getProductId(), "产品ID");
        // 检查唯一约束（供应商+产品）
        Long existCount = supplierProductMapper.selectCount(
                new LambdaQueryWrapper<SupplierProduct>()
                        .eq(SupplierProduct::getSupplierId, supplierId)
                        .eq(SupplierProduct::getProductId, productId)
        );
        if (existCount > 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "该供应商已存在此产品的供货关系");
        }
        // 校验关联的供应商、产品存在后，保存供货关系自身字段
        SupplierProduct entity = buildEntityFromDto(dto);
        LoginUser currentUser = UserContext.requireCurrentUser();
        entity.setUpdatedById(currentUser.getUserId());
        entity.setUpdatedByName(currentUser.getRealName());
        supplierProductMapper.insert(entity);
        return toVo(entity.getId());
    }

    /**
     * 编辑供货产品（重新校验供应商和产品，并校验唯一性）
     * @param supplierProductId 供货产品ID
     * @param dto 编辑供货产品请求DTO
     * @return 供货产品VO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SupplierProductVo update(Long supplierProductId, SupplierProductUpdateDto dto) {
        // 校验版本号
        if (dto.getVersion() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "编辑时版本号不能为空");
        }
        // 查询原记录
        SupplierProduct existing = supplierProductMapper.selectById(supplierProductId);
        if (existing == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "供货产品不存在");
        }
        Supplier supplier = supplierMapper.selectById(existing.getSupplierId());
        if (supplier == null || !Integer.valueOf(1).equals(supplier.getStatus())) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "关联供应商不存在或未启用");
        }
        Product product = productMapper.selectOne(
                new LambdaQueryWrapper<Product>()
                        .eq(Product::getId, existing.getProductId())
                        .select(Product::getQuantityPrecision)
        );
        if (product == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "关联产品不存在");
        }
        // 校验起订量精度
        validateQuantityPrecision(dto.getMinOrderQty(), product.getQuantityPrecision());
        SupplierProduct entity = new SupplierProduct();
        entity.setMinOrderQty(QtyUtil.toStored(dto.getMinOrderQty()));
        entity.setStatus(dto.getStatus());
        entity.setRemark(dto.getRemark());
        LoginUser currentUser = UserContext.requireCurrentUser();
        entity.setId(supplierProductId);
        entity.setVersion(dto.getVersion());
        entity.setUpdatedById(currentUser.getUserId());
        entity.setUpdatedByName(currentUser.getRealName());
        int rows = supplierProductMapper.updateById(entity);
        if (rows == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "数据已被修改，请刷新后重试");
        }
        return toVo(supplierProductId);
    }

    /**
     * 根据DTO构建供货产品实体，并校验关联主数据存在
     */
    private SupplierProduct buildEntityFromDto(SupplierProductCreateDto dto) {
        Long supplierId = IdUtil.parseRequiredLongId(dto.getSupplierId(), "供应商ID");
        Long productId = IdUtil.parseRequiredLongId(dto.getProductId(), "产品ID");
        Supplier supplier = supplierMapper.selectById(supplierId);
        if (supplier == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "供应商不存在");
        }
        if (!Integer.valueOf(1).equals(supplier.getStatus())) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "供应商不存在或未启用");
        }
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "产品不存在");
        }
        if (!Integer.valueOf(1).equals(product.getStatus())) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "产品未启用");
        }
        validateQuantityPrecision(dto.getMinOrderQty(), product.getQuantityPrecision());
        SupplierProduct entity = new SupplierProduct();
        entity.setSupplierId(supplierId);
        entity.setProductId(productId);
        entity.setMinOrderQty(QtyUtil.toStored(dto.getMinOrderQty()));
        validateInitialQuote(dto.getQuotedPurchasePrice(), dto.getQuoteValidUntil(), dto.getQuoteReason());
        entity.setQuotedPurchasePrice(QtyUtil.toStored(dto.getQuotedPurchasePrice()));
        entity.setQuotedPriceReason(dto.getQuotedPurchasePrice() == null ? "" : StrUtil.blankToDefault(dto.getQuoteReason(), "").trim());
        entity.setQuotedPriceUpdatedAt(dto.getQuotedPurchasePrice() == null ? null : LocalDateTime.now());
        entity.setQuoteValidUntil(dto.getQuotedPurchasePrice() == null ? null : dto.getQuoteValidUntil());
        entity.setScoreBasisAmount(0L);
        entity.setScoreStatus(SupplierScoreStatus.NOT_READY.name());
        entity.setStatus(dto.getStatus());
        entity.setRemark(dto.getRemark());
        return entity;
    }

    /**
     * 组建供货产品VO（关联供应商、产品主数据）
     */
    private SupplierProductVo toVo(Long id) {
        MPJLambdaWrapper<SupplierProduct> wrapper = buildBaseWrapper()
                .eq(SupplierProduct::getId, id);
        SupplierProductVo vo = supplierProductMapper.selectJoinOne(SupplierProductVo.class, wrapper);
        if (vo != null) {
            convertStoredValues(vo);
        }
        return vo;
    }

    /**
     * 构建供货产品VO查询基础Wrapper（展示字段实时关联供应商、产品主数据）
     * @return 基础查询Wrapper
     */
    private MPJLambdaWrapper<SupplierProduct> buildBaseWrapper() {
        return new MPJLambdaWrapper<SupplierProduct>()
                .selectAs(SupplierProduct::getId, SupplierProductVo::getSupplierProductId)
                .select(SupplierProduct::getSupplierId)
                .selectAs(Supplier::getSupplierCode, SupplierProductVo::getSupplierCode)
                .selectAs(Supplier::getSupplierName, SupplierProductVo::getSupplierName)
                .select(SupplierProduct::getProductId)
                .selectAs(Product::getProductCode, SupplierProductVo::getProductCode)
                .selectAs(Product::getProductName, SupplierProductVo::getProductName)
                .selectAs(Product::getUnitName, SupplierProductVo::getUnitName)
                .selectAs(Product::getQuantityPrecision, SupplierProductVo::getQuantityPrecision)
                .select(SupplierProduct::getQuotedPurchasePrice)
                .select(SupplierProduct::getQuotedPriceReason)
                .select(SupplierProduct::getQuotedPriceUpdatedAt)
                .select(SupplierProduct::getQuoteValidUntil)
                .select(SupplierProduct::getLatestPurchasePrice)
                .select(SupplierProduct::getMinOrderQty)
                .select(SupplierProduct::getQualityScore)
                .select(SupplierProduct::getPriceScore)
                .selectAs(SupplierProduct::getRecommendScore, SupplierProductVo::getAiScore)
                .select(SupplierProduct::getLastPurchaseAt)
                .select(SupplierProduct::getAvgDeliveryDays)
                .select(SupplierProduct::getScoreBasisAmount)
                .select(SupplierProduct::getScoreStatus)
                .select(SupplierProduct::getStatus)
                .select(SupplierProduct::getVersion)
                .select(SupplierProduct::getRemark)
                .select(SupplierProduct::getCreateTime)
                .select(SupplierProduct::getUpdateTime)
                .select(SupplierProduct::getUpdatedById)
                .select(SupplierProduct::getUpdatedByName)
                .leftJoin(Supplier.class, Supplier::getId, SupplierProduct::getSupplierId)
                .leftJoin(Product.class, Product::getId, SupplierProduct::getProductId);
    }

    /**
     * 100倍存储字段转业务值（评分÷100，单价÷100，起订量÷100）
     * @param vo 供货产品VO
     */
    private void convertStoredValues(SupplierProductVo vo) {
        vo.setQualityScore(QtyUtil.toDecimal(vo.getQualityScore()));
        vo.setPriceScore(QtyUtil.toDecimal(vo.getPriceScore()));
        vo.setAiScore(QtyUtil.toDecimal(vo.getAiScore()));
        vo.setLatestPurchasePrice(QtyUtil.toDecimal(vo.getLatestPurchasePrice()));
        vo.setQuotedPurchasePrice(QtyUtil.toDecimal(vo.getQuotedPurchasePrice()));
        vo.setScoreBasisAmount(QtyUtil.toDecimal(vo.getScoreBasisAmount()));
        vo.setMinOrderQty(QtyUtil.toDecimal(vo.getMinOrderQty()));
    }

    /**
     * 验证供货产品起订量精度
     */
    private void validateQuantityPrecision(BigDecimal quantity, Integer precisionValue) {
        int precision = precisionValue == null ? 0 : precisionValue;
        if (quantity == null || quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > precision) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "最小起订量必须大于0，且最多保留 " + precision + " 位小数");
        }
    }

    /**
     更新供货产品报价
     * @param supplierProductId 供货产品ID
     * @param dto 更新报价DTO
      */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public SupplierProductVo updateQuote(Long supplierProductId, SupplierProductQuoteDto dto) {
        if (dto.getVersion() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "更新报价时版本号不能为空");
        }
        SupplierProduct existing = supplierProductMapper.selectById(supplierProductId);
        if (existing == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "供货关系不存在");
        }
        // 与凌晨校正统一先锁供应商、再修改供货关系，避免 SP→supplier 与 supplier→SP 的行锁环。
        if (supplierMapper.lockByIdForUpdate(existing.getSupplierId()) == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "供应商不存在");
        }
        // 首次查询仅用于定位锁；等待期间供货关系可能变化，不能沿用锁前读取的记录。
        SupplierProduct current = supplierProductMapper.selectById(supplierProductId);
        if (current == null || !java.util.Objects.equals(existing.getSupplierId(), current.getSupplierId())) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "供货关系已变化，请刷新后重试");
        }
        if (!java.util.Objects.equals(dto.getVersion(), current.getVersion())) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "供货关系数据已变化，请刷新后重试");
        }
        // 1. 验证报价参数
        validateQuote(dto.getQuotedPurchasePrice(), dto.getQuoteValidUntil(), dto.getReason());
        LoginUser currentUser = UserContext.requireCurrentUser();
        Long storedQuote = QtyUtil.toStored(dto.getQuotedPurchasePrice());
        LocalDateTime quoteUpdatedAt = dto.getQuotedPurchasePrice() == null ? null : LocalDateTime.now();
        // updateById 默认忽略 null；清空报价必须显式 SET NULL，才能同时满足报价三字段成组约束。
        LambdaUpdateWrapper<SupplierProduct> update = new LambdaUpdateWrapper<SupplierProduct>()
                .eq(SupplierProduct::getId, supplierProductId)
                .eq(SupplierProduct::getVersion, dto.getVersion())
                .set(SupplierProduct::getQuotedPurchasePrice, storedQuote)
                .set(SupplierProduct::getQuotedPriceReason, dto.getQuotedPurchasePrice() == null ? "" : StrUtil.blankToDefault(dto.getReason(), "").trim())
                .set(SupplierProduct::getQuotedPriceUpdatedAt, quoteUpdatedAt)
                .set(SupplierProduct::getQuoteValidUntil, dto.getQuotedPurchasePrice() == null ? null : dto.getQuoteValidUntil())
                .set(SupplierProduct::getUpdatedById, currentUser.getUserId())
                .set(SupplierProduct::getUpdatedByName, currentUser.getRealName())
                .set(SupplierProduct::getVersion, dto.getVersion() + 1);
        if (supplierProductMapper.update(null, update) == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "供货关系数据已变化，请刷新后重试");
        }
        // 报价与评分同事务更新，失败时一起回滚，避免报价已生效而价格分仍为旧值。
        supplierScoreRecalculateService.recalcForQuoteChange(supplierProductId, dto.getReason());
        return toVo(supplierProductId);
    }

    /**
     * 验证供货产品初始报价
     */
    private void validateInitialQuote(BigDecimal price, LocalDate validUntil, String reason) {
        if (price == null && validUntil == null && StrUtil.isBlank(reason)) {
            return;
        }
        if (price == null && validUntil == null && StrUtil.isNotBlank(reason)) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "未填写报价时，报价原因不应填写");
        }
        validateQuote(price, validUntil, reason);
    }

    /**
     * 验证供货产品报价
     */
    private void validateQuote(BigDecimal price, LocalDate validUntil, String reason) {
        if (price == null && validUntil == null) {
            return;
        }
        if (price == null || validUntil == null || StrUtil.isBlank(reason)) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "报价、有效截止日和原因必须同时填写；清空报价时价格与截止日都传空");
        }
        if (price.signum() <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "报价必须大于0");
        }
        if (price.stripTrailingZeros().scale() > QtyUtil.SCALE) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "报价最多保留两位小数");
        }
        if (validUntil.isBefore(LocalDate.now())) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "报价有效截止日不能早于当前业务日");
        }
    }

    /**
     * 批量修改供货产品状态（最佳努力模式，乐观锁实现）
     * @param dto 批量状态修改请求DTO
     * @return 失败的供货产品信息：key=供货产品ID，value=失败原因；空 map 表示全部成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, String> batchUpdateStatus(SupplierProductBatchStatusDto dto) {
        LoginUser currentUser = UserContext.requireCurrentUser();
        Map<String, String> failures = new LinkedHashMap<>();
        List<String> ids = dto.getSupplierProductIds();
        List<Long> supplierProductIds = IdUtil.parseRequiredLongIds(ids, "供货关系ID");
        Map<String, Integer> versionMap = dto.getVersionBySupplierProductId();
        Integer targetStatus = dto.getStatus();
        // 遍历供货产品ID列表，更新状态
        for (int index = 0; index < ids.size(); index++) {
            String id = ids.get(index);
            Integer expectedVersion = versionMap.get(id);
            if (expectedVersion == null) {
                failures.put(id, "未找到版本号");
                continue;
            }
            Long supplierProductId = supplierProductIds.get(index);
            SupplierProduct entity = new SupplierProduct();
            entity.setId(supplierProductId);
            entity.setStatus(targetStatus);
            entity.setVersion(expectedVersion);
            entity.setUpdatedById(currentUser.getUserId());
            entity.setUpdatedByName(currentUser.getRealName());
            int rows = supplierProductMapper.updateById(entity);
            if (rows == 0) {
                failures.put(id, "供货产品不存在或数据已发生变化，请刷新后重试");
            }
        }

        return failures;
    }

    /**
     * 批量删除供货产品（逻辑删除，最佳努力模式，乐观锁实现）
     * @param dto 批量删除请求DTO
     * @return 失败的供货产品信息：key=供货产品ID，value=失败原因；空 map 表示全部成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, String> batchDelete(SupplierProductBatchDeleteDto dto) {
        Map<String, String> failures = new LinkedHashMap<>();
        List<String> ids = dto.getSupplierProductIds();
        List<Long> supplierProductIds = IdUtil.parseRequiredLongIds(ids, "供货关系ID");
        for (int index = 0; index < ids.size(); index++) {
            String idStr = ids.get(index);
            Long supplierProductId = supplierProductIds.get(index);
            Integer expectedVersion = dto.getVersionBySupplierProductId().get(idStr);
            if (expectedVersion == null) {
                failures.put(idStr, "未找到版本号");
                continue;
            }
            int rows = supplierProductMapper.deleteByIdWithVersion(supplierProductId, expectedVersion);
            if (rows == 0) {
                failures.put(idStr, "供货产品不存在或数据已发生变化，请刷新后重试");
            }
        }
        return failures;
    }
}
