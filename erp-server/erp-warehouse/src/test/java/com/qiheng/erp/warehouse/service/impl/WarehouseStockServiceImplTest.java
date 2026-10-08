package com.qiheng.erp.warehouse.service.impl;

import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.qiheng.erp.warehouse.domain.warehousestock.entity.WarehouseStock;
import com.qiheng.erp.warehouse.domain.warehousestock.vo.WarehouseStockSummaryVo;
import com.qiheng.erp.warehouse.domain.warehousestock.vo.WarehouseStockVo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WarehouseStockServiceImplTest {

    @BeforeEach
    void initializeMybatisPlusMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), WarehouseStock.class);
    }

    @Test
    void shouldConvertStoredSafetyStockAndKeepMissingProductDataNull() {
        WarehouseStockServiceImpl service = new WarehouseStockServiceImpl();
        WarehouseStockVo stock = new WarehouseStockVo();
        stock.setStockQty(BigDecimal.valueOf(1234));
        stock.setLockedQty(BigDecimal.valueOf(234));
        stock.setSafetyStockQtyStored(550L);

        WarehouseStockVo missingProduct = new WarehouseStockVo();
        missingProduct.setStockQty(BigDecimal.valueOf(100));
        missingProduct.setLockedQty(BigDecimal.ZERO);

        ReflectionTestUtils.invokeMethod(service, "convertQtyValues", List.of(stock, missingProduct));

        assertThat(stock.getStockQty()).isEqualByComparingTo("12.34");
        assertThat(stock.getLockedQty()).isEqualByComparingTo("2.34");
        assertThat(stock.getAvailableQty()).isEqualByComparingTo("10.00");
        assertThat(stock.getSafetyStockQty()).isEqualByComparingTo("5.50");
        assertThat(stock.getSafetyStockQtyStored()).isNull();
        assertThat(missingProduct.getSafetyStockQty()).isNull();
    }

    @Test
    void shouldCountLowStockAtInclusiveSafetyThreshold() {
        WarehouseStockServiceImpl service = new WarehouseStockServiceImpl();
        WarehouseStockVo atThreshold = stock(10, 0, 10);
        WarehouseStockVo noAvailable = stock(10, 10, 5);

        WarehouseStockSummaryVo summary = ReflectionTestUtils.invokeMethod(
                service, "buildSummary", List.of(atThreshold, noAvailable));

        assertThat(summary.getLowStockCount()).isEqualTo(1);
        assertThat(summary.getNoAvailableCount()).isEqualTo(1);
    }

    @Test
    void shouldGroupRiskOrBeforeCombiningWithOtherFilters() {
        WarehouseStockServiceImpl service = new WarehouseStockServiceImpl();
        MPJLambdaWrapper<WarehouseStock> wrapper = new MPJLambdaWrapper<>();
        wrapper.eq(WarehouseStock::getWarehouseId, 123L);

        ReflectionTestUtils.invokeMethod(service, "applyRiskOnly", wrapper, true);
        String sqlSegment = wrapper.getSqlSegment();

        assertThat(sqlSegment).contains("warehouse_id =");
        assertThat(sqlSegment).contains("AND ((t.stock_qty - t.locked_qty) <= 0");
        assertThat(sqlSegment).contains("OR (t1.safety_stock_qty > 0 AND");
        assertThat(sqlSegment).contains("<= t1.safety_stock_qty))");
    }

    private WarehouseStockVo stock(long quantity, long locked, long safety) {
        WarehouseStockVo stock = new WarehouseStockVo();
        stock.setStockQty(BigDecimal.valueOf(quantity));
        stock.setLockedQty(BigDecimal.valueOf(locked));
        stock.setAvailableQty(BigDecimal.valueOf(quantity - locked));
        stock.setSafetyStockQty(BigDecimal.valueOf(safety));
        return stock;
    }
}
