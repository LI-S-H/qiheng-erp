package com.qiheng.erp.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.qiheng.erp.common.annotation.DistributedLock;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.product.domain.dto.ProductBatchStatusDto;
import com.qiheng.erp.product.domain.dto.ProductSaveDto;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.product.domain.entity.ProductCategory;
import com.qiheng.erp.product.mapper.ProductCategoryMapper;
import com.qiheng.erp.product.mapper.ProductMapper;
import com.qiheng.erp.product.service.IProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductCategoryServiceImplTest {

    @Mock
    private ProductCategoryMapper productCategoryMapper;
    @Mock
    private ProductMapper productMapper;
    @Mock
    private IProductService productService;

    @BeforeEach
    void initializeMybatisPlusMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Product.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ProductCategory.class);
    }

    @Test
    void shouldDisableDescendantsAndTheirProductsThroughProductService() {
        ProductCategoryServiceImpl service = newService();
        when(productCategoryMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                new ProductCategory().setId(1L).setParentId(0L),
                new ProductCategory().setId(2L).setParentId(1L)));
        when(productMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                new Product().setId(101L).setCategoryId(1L),
                new Product().setId(102L).setCategoryId(2L)));

        service.updateBatchStatus(List.of("1"), 0);

        ArgumentCaptor<ProductBatchStatusDto> captor = ArgumentCaptor.forClass(ProductBatchStatusDto.class);
        verify(productService).updateBatchStatus(captor.capture());
        assertEquals(List.of("101", "102"), captor.getValue().getProductIds());
        assertEquals(0, captor.getValue().getStatus());
        verify(productCategoryMapper).update(org.mockito.ArgumentMatchers.isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void shouldNotDisableCategoriesWhenAnyProductHasUnfinishedBusinessReference() {
        ProductCategoryServiceImpl service = newService();
        when(productCategoryMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                new ProductCategory().setId(1L).setParentId(0L)));
        when(productMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                new Product().setId(101L).setCategoryId(1L)));
        doThrow(new BizException(500, "产品被未完成业务引用，无法停用"))
                .when(productService).updateBatchStatus(any(ProductBatchStatusDto.class));

        assertThrows(BizException.class, () -> service.updateBatchStatus(List.of("1"), 0));

        verify(productService).updateBatchStatus(any(ProductBatchStatusDto.class));
        verifyNoInteractionsAfterCategoryList(productCategoryMapper);
    }

    @Test
    void shouldUseOneSharedLockForEveryProductAndCategoryWritePath() throws NoSuchMethodException {
        assertSharedLock(ProductCategoryServiceImpl.class.getMethod("updateBatchStatus", List.class, Integer.class));
        assertSharedLock(ProductCategoryServiceImpl.class.getMethod("removeBatch", List.class));
        assertSharedLock(ProductCategoryServiceImpl.class.getMethod("update", ProductCategory.class));
        assertSharedLock(ProductServiceImpl.class.getMethod("add", ProductSaveDto.class));
        assertSharedLock(ProductServiceImpl.class.getMethod("updateBatchStatus", ProductBatchStatusDto.class));
        assertSharedLock(ProductServiceImpl.class.getMethod("updateStatus", Long.class, Integer.class));
        assertSharedLock(ProductServiceImpl.class.getMethod("deleteBatch", List.class));
        assertSharedLock(ProductServiceImpl.class.getMethod("update", Long.class, ProductSaveDto.class));
    }

    private ProductCategoryServiceImpl newService() {
        ProductCategoryServiceImpl service = new ProductCategoryServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", productCategoryMapper);
        ReflectionTestUtils.setField(service, "productCategoryMapper", productCategoryMapper);
        ReflectionTestUtils.setField(service, "productMapper", productMapper);
        ReflectionTestUtils.setField(service, "productService", productService);
        return service;
    }

    private void assertSharedLock(Method method) {
        DistributedLock lock = method.getAnnotation(DistributedLock.class);
        assertEquals("'product:category:global'", lock.key());
    }

    private void verifyNoInteractionsAfterCategoryList(ProductCategoryMapper mapper) {
        verify(mapper).selectList(any(Wrapper.class));
        verify(mapper, org.mockito.Mockito.never()).update(org.mockito.ArgumentMatchers.isNull(), any(LambdaUpdateWrapper.class));
    }
}
