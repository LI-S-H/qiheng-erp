package com.qiheng.erp.product.service.impl;

import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.product.domain.dto.ProductSaveDto;
import com.qiheng.erp.product.domain.entity.Product;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 产品请求转实体的存储规则测试。
 *
 * <p>安全库存在数据库按 100 倍 BIGINT 整数存储，请求以业务真实值传入，
 * 这里覆盖转换正确性与小数位越界拦截。</p>
 *
 * @author Li
 * @since 2026-09-06
 */
class ProductServiceImplSafetyStockTest {

    private final ProductServiceImpl service = new ProductServiceImpl();

    @Test
    void shouldConvertSafetyStockQtyToHundredTimesStoredValue() {
        ProductSaveDto dto = dto(new BigDecimal("12.50"), 2);

        Product product = toEntity(dto);

        // 12.50 必须落库为 1250，而不是 12 或 12.5
        assertEquals(1250L, product.getSafetyStockQty());
    }

    @Test
    void shouldConvertIntegerSafetyStockForDiscreteUnit() {
        ProductSaveDto dto = dto(new BigDecimal("12"), 0);

        Product product = toEntity(dto);

        assertEquals(1200L, product.getSafetyStockQty());
    }

    @Test
    void shouldKeepTrailingZeroValueEquivalent() {
        // 12 与 12.00 在精度 2 下都是合法输入，落库值必须一致
        assertEquals(toEntity(dto(new BigDecimal("12"), 2)).getSafetyStockQty(),
                toEntity(dto(new BigDecimal("12.00"), 2)).getSafetyStockQty());
    }

    @Test
    void shouldRejectSafetyStockQtyExceedingQuantityPrecision() {
        // 离散单位（精度 0）不允许出现小数，否则落库会被静默四舍五入
        ProductSaveDto dto = dto(new BigDecimal("12.5"), 0);

        BizException exception = assertThrows(BizException.class, () -> toEntity(dto));

        assertTrue(exception.getMessage().contains("最多保留 0 位小数"));
    }

    @Test
    void shouldRejectTwoDecimalsWhenPrecisionIsOne() {
        ProductSaveDto dto = dto(new BigDecimal("12.55"), 1);

        BizException exception = assertThrows(BizException.class, () -> toEntity(dto));

        assertTrue(exception.getMessage().contains("最多保留 1 位小数"));
    }

    @Test
    void shouldParseCategoryIdFromStringAndAllowNull() {
        ProductSaveDto dto = dto(new BigDecimal("1"), 0);
        dto.setCategoryId("1910000000000000111");
        assertEquals(1910000000000000111L, toEntity(dto).getCategoryId());

        dto.setCategoryId(null);
        assertNull(toEntity(dto).getCategoryId());
    }

    @Test
    void shouldNotWriteProductCodeOnConversion() {
        // 产品编码由后端生成且创建后不可修改，转换结果必须不含编码
        assertNull(toEntity(dto(new BigDecimal("1"), 0)).getProductCode());
    }

    private Product toEntity(ProductSaveDto dto) {
        return (Product) ReflectionTestUtils.invokeMethod(service, "toEntity", dto, null);
    }

    private static ProductSaveDto dto(BigDecimal safetyStockQty, int quantityPrecision) {
        ProductSaveDto dto = new ProductSaveDto();
        dto.setProductName("测试产品");
        dto.setBrandName("测试品牌");
        dto.setUnitName("箱");
        dto.setQuantityPrecision(quantityPrecision);
        dto.setSpecification("规格");
        dto.setReferencePurchasePrice(new BigDecimal("10.00"));
        dto.setReferenceSalePrice(new BigDecimal("20.00"));
        dto.setSafetyStockQty(safetyStockQty);
        dto.setStatus(1);
        dto.setRemark("");
        return dto;
    }
}
