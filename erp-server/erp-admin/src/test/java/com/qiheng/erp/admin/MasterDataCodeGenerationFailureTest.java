package com.qiheng.erp.admin;

import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.util.CodeNoDefinition;
import com.qiheng.erp.common.util.CodeNoGenerator;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.product.mapper.ProductMapper;
import com.qiheng.erp.product.service.impl.ProductServiceImpl;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierCreateDto;
import com.qiheng.erp.purchase.domain.supplier.entity.Supplier;
import com.qiheng.erp.purchase.mapper.SupplierMapper;
import com.qiheng.erp.purchase.service.impl.SupplierServiceImpl;
import com.qiheng.erp.sales.domain.customer.dto.CustomerCreateDto;
import com.qiheng.erp.sales.domain.customer.entity.Customer;
import com.qiheng.erp.sales.mapper.CustomerMapper;
import com.qiheng.erp.sales.service.impl.CustomerServiceImpl;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.warehouse.domain.warehouse.entity.Warehouse;
import com.qiheng.erp.warehouse.mapper.WarehouseMapper;
import com.qiheng.erp.warehouse.service.impl.WarehouseServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编码基础设施不可用时，主数据创建必须在写入前失败，不能产生无编码或重复编码的数据。
 */
@ExtendWith(MockitoExtension.class)
class MasterDataCodeGenerationFailureTest {

    @Mock
    private CodeNoGenerator codeNoGenerator;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private SupplierMapper supplierMapper;

    @Mock
    private CustomerMapper customerMapper;

    @Mock
    private WarehouseMapper warehouseMapper;

    @Test
    void productCreateShouldNotInsertWhenCodeGenerationFails() {
        ProductServiceImpl service = new ProductServiceImpl();
        ReflectionTestUtils.setField(service, "codeNoGenerator", codeNoGenerator);
        ReflectionTestUtils.setField(service, "productMapper", productMapper);
        stubCodeGenerationFailure();

        assertThrows(BizException.class, () -> service.add(new Product()));

        verify(productMapper).findMaxProductCodeSequence(anyString(), anyInt(), anyInt());
        verify(productMapper, org.mockito.Mockito.never()).insert(any(Product.class));
    }

    @Test
    void supplierCreateShouldNotInsertWhenCodeGenerationFails() {
        SupplierServiceImpl service = new SupplierServiceImpl();
        ReflectionTestUtils.setField(service, "codeNoGenerator", codeNoGenerator);
        ReflectionTestUtils.setField(service, "supplierMapper", supplierMapper);
        stubCodeGenerationFailure();

        try (MockedStatic<UserContext> userContext = mockStatic(UserContext.class)) {
            userContext.when(UserContext::requireCurrentUser).thenReturn(new LoginUser());

            assertThrows(BizException.class, () -> service.create(new SupplierCreateDto()));
        }

        verify(supplierMapper).findMaxSupplierCodeSequence(anyString(), anyInt(), anyInt());
        verify(supplierMapper, org.mockito.Mockito.never()).insert(any(Supplier.class));
    }

    @Test
    void customerCreateShouldNotInsertWhenCodeGenerationFails() {
        CustomerServiceImpl service = new CustomerServiceImpl();
        ReflectionTestUtils.setField(service, "codeNoGenerator", codeNoGenerator);
        ReflectionTestUtils.setField(service, "customerMapper", customerMapper);
        stubCodeGenerationFailure();

        try (MockedStatic<UserContext> userContext = mockStatic(UserContext.class)) {
            userContext.when(UserContext::requireCurrentUser).thenReturn(new LoginUser());

            assertThrows(BizException.class, () -> service.create(new CustomerCreateDto()));
        }

        verify(customerMapper).findMaxCustomerCodeSequence(anyString(), anyInt(), anyInt());
        verify(customerMapper, org.mockito.Mockito.never()).insert(any(Customer.class));
    }

    @Test
    void warehouseCreateShouldNotInsertWhenCodeGenerationFails() {
        WarehouseServiceImpl service = new WarehouseServiceImpl();
        ReflectionTestUtils.setField(service, "codeNoGenerator", codeNoGenerator);
        ReflectionTestUtils.setField(service, "warehouseMapper", warehouseMapper);
        stubCodeGenerationFailure();

        assertThrows(BizException.class, () -> service.add(new Warehouse()));

        verify(warehouseMapper).findMaxWarehouseCodeSequence(anyString(), anyInt(), anyInt());
        verify(warehouseMapper, org.mockito.Mockito.never()).insert(any(Warehouse.class));
    }

    private void stubCodeGenerationFailure() {
        when(codeNoGenerator.nextNo(any(CodeNoDefinition.class), any(LongSupplier.class)))
                .thenAnswer(invocation -> {
                    LongSupplier maxSequenceSupplier = invocation.getArgument(1);
                    maxSequenceSupplier.getAsLong();
                    throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "编码生成不可用");
                });
    }
}