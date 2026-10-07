package com.qiheng.erp.purchase.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.product.mapper.ProductMapper;
import com.qiheng.erp.purchase.domain.supplier.entity.Supplier;
import com.qiheng.erp.purchase.domain.supplierproduct.dto.SupplierProductCreateDto;
import com.qiheng.erp.purchase.domain.supplierproduct.dto.SupplierProductUpdateDto;
import com.qiheng.erp.purchase.domain.supplierproduct.dto.SupplierProductQuoteDto;
import com.qiheng.erp.purchase.service.SupplierScoreRecalculateService;
import com.qiheng.erp.purchase.domain.supplierproduct.entity.SupplierProduct;
import com.qiheng.erp.purchase.mapper.SupplierMapper;
import com.qiheng.erp.purchase.mapper.SupplierProductMapper;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SupplierProductServiceImpl 单元测试。
 * <p>
 * 重点验证本次重构的几处改动:
 * <ul>
 *     <li>buildEntityFromDto 合并 supplier 查询为 1 次</li>
 *     <li>create 的供应商/产品校验顺序</li>
 *     <li>update 的乐观锁语义</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SupplierProductServiceImplTest {

    @Mock
    private SupplierProductMapper supplierProductMapper;

    @Mock
    private SupplierMapper supplierMapper;

    @Mock
    private ProductMapper productMapper;
    @Mock
    private SupplierScoreRecalculateService recalculateService;

    private SupplierProductServiceImpl service;

    private MockedStatic<UserContext> userContextMock;

    @BeforeEach
    void setUp() {
        // Mockito 环境下 MyBatis-Plus 的 lambda cache 未自动初始化,需手动注入 TableInfo
        // 否则 LambdaQueryWrapper 反射 Product/Supplier 等类时会抛 can not find lambda cache
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Product.class);
        TableInfoHelper.initTableInfo(assistant, Supplier.class);
        TableInfoHelper.initTableInfo(assistant, SupplierProduct.class);

        service = new SupplierProductServiceImpl();
        ReflectionTestUtils.setField(service, "supplierProductMapper", supplierProductMapper);
        ReflectionTestUtils.setField(service, "supplierMapper", supplierMapper);
        ReflectionTestUtils.setField(service, "productMapper", productMapper);
        ReflectionTestUtils.setField(service, "supplierScoreRecalculateService", recalculateService);

        LoginUser user = new LoginUser();
        user.setUserId(1L);
        user.setRealName("admin");
        userContextMock = mockStatic(UserContext.class);
        userContextMock.when(UserContext::requireCurrentUser).thenReturn(user);
    }

    @AfterEach
    void tearDown() {
        userContextMock.close();
    }

    @Test
    void quoteUpdateLocksSupplierBeforeWritingProductAndRecalculating() {
        SupplierProduct existing = new SupplierProduct().setId(9L).setSupplierId(100L).setVersion(1);
        when(supplierProductMapper.selectById(9L)).thenReturn(existing);
        when(supplierMapper.lockByIdForUpdate(100L)).thenReturn(100L);
        when(supplierProductMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        SupplierProductQuoteDto dto = new SupplierProductQuoteDto();
        dto.setVersion(1);
        dto.setQuotedPurchasePrice(new BigDecimal("10.00"));
        dto.setQuoteValidUntil(LocalDate.now().plusDays(1));
        dto.setReason("调整报价");

        service.updateQuote(9L, dto);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(supplierMapper, supplierProductMapper, recalculateService);
        order.verify(supplierMapper).lockByIdForUpdate(100L);
        order.verify(supplierProductMapper).selectById(9L);
        order.verify(supplierProductMapper).update(org.mockito.ArgumentMatchers.isNull(), any());
        order.verify(recalculateService).recalcForQuoteChange(9L, "调整报价");
    }

    @Test
    void quoteUpdateRejectsVersionChangedWhileWaitingForSupplierLock() {
        SupplierProduct before = new SupplierProduct().setId(9L).setSupplierId(100L).setVersion(1);
        SupplierProduct after = new SupplierProduct().setId(9L).setSupplierId(100L).setVersion(2);
        when(supplierProductMapper.selectById(9L)).thenReturn(before, after);
        when(supplierMapper.lockByIdForUpdate(100L)).thenReturn(100L);
        SupplierProductQuoteDto dto = new SupplierProductQuoteDto();
        dto.setVersion(1);

        assertThrows(BizException.class, () -> service.updateQuote(9L, dto));

        verify(supplierProductMapper, never()).update(org.mockito.ArgumentMatchers.isNull(), any());
        verify(recalculateService, never()).recalcForQuoteChange(any(), any());
    }

    // ===== create 用例 =====

    @Test
    void createShouldThrowWhenSupplierNotExist() {
        SupplierProductCreateDto dto = createDto();
        when(supplierMapper.selectById(100L)).thenReturn(null);

        BizException exception = assertThrows(BizException.class, () -> service.create(dto));
        assertEquals(ErrorCode.DATA_NOT_FOUND.getCode(), exception.getCode());
        assertEquals("供应商不存在", exception.getMessage());
        verify(supplierProductMapper, never()).insert(any(SupplierProduct.class));
    }

    @Test
    void createShouldThrowWhenSupplierDisabled() {
        SupplierProductCreateDto dto = createDto();
        Supplier disabled = new Supplier();
        disabled.setId(100L);
        disabled.setStatus(0);
        when(supplierMapper.selectById(100L)).thenReturn(disabled);

        BizException exception = assertThrows(BizException.class, () -> service.create(dto));
        assertEquals("供应商不存在或未启用", exception.getMessage());
    }

    @Test
    void createShouldThrowWhenProductNotExist() {
        SupplierProductCreateDto dto = createDto();
        Supplier enabled = enabledSupplier();
        when(supplierMapper.selectById(100L)).thenReturn(enabled);
        when(productMapper.selectById(200L)).thenReturn(null);

        BizException exception = assertThrows(BizException.class, () -> service.create(dto));
        assertEquals("产品不存在", exception.getMessage());
    }

    @Test
    void createShouldThrowWhenProductDisabled() {
        SupplierProductCreateDto dto = createDto();
        when(supplierMapper.selectById(100L)).thenReturn(enabledSupplier());
        Product product = new Product();
        product.setId(200L);
        product.setStatus(0);
        product.setQuantityPrecision(0);
        when(productMapper.selectById(200L)).thenReturn(product);

        BizException exception = assertThrows(BizException.class, () -> service.create(dto));
        assertEquals("产品未启用", exception.getMessage());
    }

    @Test
    void createShouldThrowWhenMinOrderQtyZero() {
        SupplierProductCreateDto dto = createDto();
        dto.setMinOrderQty(BigDecimal.ZERO);
        when(supplierMapper.selectById(100L)).thenReturn(enabledSupplier());
        when(productMapper.selectById(200L)).thenReturn(productWithPrecision(0));

        BizException exception = assertThrows(BizException.class, () -> service.create(dto));
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), exception.getCode());
        assertEquals("最小起订量必须大于0，且最多保留 0 位小数", exception.getMessage());
    }

    @Test
    void createShouldThrowWhenMinOrderQtyPrecisionExceed() {
        SupplierProductCreateDto dto = createDto();
        dto.setMinOrderQty(BigDecimal.valueOf(10.123));
        when(supplierMapper.selectById(100L)).thenReturn(enabledSupplier());
        when(productMapper.selectById(200L)).thenReturn(productWithPrecision(2));

        BizException exception = assertThrows(BizException.class, () -> service.create(dto));
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), exception.getCode());
    }

    @Test
    void createShouldThrowWhenSupplierProductAlreadyExists() {
        SupplierProductCreateDto dto = createDto();
        when(supplierProductMapper.selectCount(any())).thenReturn(1L);

        BizException exception = assertThrows(BizException.class, () -> service.create(dto));
        assertEquals(ErrorCode.OPERATION_FAILED.getCode(), exception.getCode());
        assertEquals("该供应商已存在此产品的供货关系", exception.getMessage());
        verify(supplierProductMapper, never()).insert(any(SupplierProduct.class));
        verify(supplierMapper, never()).selectById(any());
    }

    @Test
    void createShouldCallSupplierMapperOnlyOnce() {
        SupplierProductCreateDto dto = createDto();
        when(supplierMapper.selectById(100L)).thenReturn(enabledSupplier());
        when(productMapper.selectById(200L)).thenReturn(productWithPrecision(0));
        when(supplierProductMapper.selectCount(any())).thenReturn(0L);
        // 让 insert 抛异常,跳过 toVo() 的 MPJ 调用
        when(supplierProductMapper.insert(any(SupplierProduct.class)))
                .thenThrow(new RuntimeException("test stops here"));

        assertThrows(RuntimeException.class, () -> service.create(dto));

        // 合并 supplier 查询后,supplierMapper.selectById 只调 1 次
        verify(supplierMapper, times(1)).selectById(100L);
    }

    // ===== update 用例 =====

    @Test
    void updateShouldThrowWhenVersionMissing() {
        SupplierProductUpdateDto dto = new SupplierProductUpdateDto();
        dto.setMinOrderQty(BigDecimal.TEN);
        dto.setStatus(1);
        dto.setRemark("");

        BizException exception = assertThrows(BizException.class, () -> service.update(1L, dto));
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), exception.getCode());
        assertEquals("编辑时版本号不能为空", exception.getMessage());
    }

    @Test
    void updateShouldThrowWhenSupplierProductNotExist() {
        SupplierProductUpdateDto dto = new SupplierProductUpdateDto();
        dto.setVersion(0);
        dto.setMinOrderQty(BigDecimal.TEN);
        dto.setStatus(1);
        dto.setRemark("");
        when(supplierProductMapper.selectById(1L)).thenReturn(null);

        BizException exception = assertThrows(BizException.class, () -> service.update(1L, dto));
        assertEquals("供货产品不存在", exception.getMessage());
    }

    @Test
    void updateShouldThrowWhenRelatedProductNotExist() {
        SupplierProductUpdateDto dto = new SupplierProductUpdateDto();
        dto.setVersion(0);
        dto.setMinOrderQty(BigDecimal.TEN);
        dto.setStatus(1);
        dto.setRemark("");
        SupplierProduct existing = new SupplierProduct();
        existing.setId(1L);
        existing.setSupplierId(100L);
        existing.setProductId(200L);
        when(supplierProductMapper.selectById(1L)).thenReturn(existing);
        when(supplierMapper.selectById(100L)).thenReturn(enabledSupplier());
        when(productMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        BizException exception = assertThrows(BizException.class, () -> service.update(1L, dto));
        assertEquals("关联产品不存在", exception.getMessage());
    }

    @Test
    void updateShouldThrowWhenOptimisticLockFails() {
        SupplierProductUpdateDto dto = new SupplierProductUpdateDto();
        dto.setVersion(0);
        dto.setMinOrderQty(BigDecimal.TEN);
        dto.setStatus(1);
        dto.setRemark("");
        SupplierProduct existing = new SupplierProduct();
        existing.setId(1L);
        existing.setSupplierId(100L);
        existing.setProductId(200L);
        existing.setVersion(0);
        when(supplierProductMapper.selectById(1L)).thenReturn(existing);
        when(supplierMapper.selectById(100L)).thenReturn(enabledSupplier());
        when(productMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(productWithPrecision(0));
        when(supplierProductMapper.updateById(any(SupplierProduct.class))).thenReturn(0);

        BizException exception = assertThrows(BizException.class, () -> service.update(1L, dto));
        assertEquals("数据已被修改，请刷新后重试", exception.getMessage());
    }

    @Test
    void updateShouldSucceedWhenNormal() {
        SupplierProductUpdateDto dto = new SupplierProductUpdateDto();
        dto.setVersion(0);
        dto.setMinOrderQty(BigDecimal.TEN);
        dto.setStatus(1);
        dto.setRemark("更新备注");
        SupplierProduct existing = new SupplierProduct();
        existing.setId(1L);
        existing.setSupplierId(100L);
        existing.setProductId(200L);
        existing.setVersion(0);
        when(supplierProductMapper.selectById(1L)).thenReturn(existing);
        when(supplierMapper.selectById(100L)).thenReturn(enabledSupplier());
        when(productMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(productWithPrecision(0));
        // 让 updateById 抛异常,跳过 toVo() 的 MPJ 调用
        when(supplierProductMapper.updateById(any(SupplierProduct.class)))
                .thenThrow(new RuntimeException("test stops here"));

        assertThrows(RuntimeException.class, () -> service.update(1L, dto));

        verify(supplierProductMapper, times(1)).updateById(any(SupplierProduct.class));
    }

    // ===== 工具 =====

    private SupplierProductCreateDto createDto() {
        SupplierProductCreateDto dto = new SupplierProductCreateDto();
        dto.setSupplierId("100");
        dto.setProductId("200");
        dto.setMinOrderQty(BigDecimal.TEN);
        dto.setStatus(1);
        dto.setRemark("");
        return dto;
    }

    private Supplier enabledSupplier() {
        Supplier supplier = new Supplier();
        supplier.setId(100L);
        supplier.setStatus(1);
        return supplier;
    }

    private Product productWithPrecision(int precision) {
        Product product = new Product();
        product.setId(200L);
        product.setStatus(1);
        product.setQuantityPrecision(precision);
        return product;
    }
}
