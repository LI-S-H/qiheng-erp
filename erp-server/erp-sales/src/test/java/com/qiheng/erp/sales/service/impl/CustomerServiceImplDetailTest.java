package com.qiheng.erp.sales.service.impl;

import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.sales.domain.customer.entity.Customer;
import com.qiheng.erp.sales.domain.customer.vo.CustomerVo;
import com.qiheng.erp.sales.mapper.CustomerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 客户详情查询的输入输出测试：按 ID 返回展示字段，信用额度还原为业务值，缺失时明确报错。
 *
 * @author Li
 * @since 2026-11-23
 */
class CustomerServiceImplDetailTest {

    private final CustomerMapper customerMapper = mock(CustomerMapper.class);
    private final CustomerServiceImpl service = new CustomerServiceImpl();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "customerMapper", customerMapper);
    }

    @Test
    void shouldReturnCustomerDetailWithBusinessCreditLimit() {
        when(customerMapper.selectById(2020000000000000001L)).thenReturn(customer(2020000000000000001L));

        CustomerVo vo = service.getDetailById(2020000000000000001L);

        assertEquals(2020000000000000001L, vo.getCustomerId());
        assertEquals("C001", vo.getCustomerCode());
        assertEquals("上海林间便利连锁", vo.getCustomerName());
        assertEquals(1, vo.getStatus());
        // 信用额度落库为 100 倍整数，返回给业务方必须是 180000，而不是 18000000。
        assertEquals(0, vo.getCreditLimit().compareTo(new BigDecimal("180000")));
        assertEquals("2020000000000000001", String.valueOf(vo.getCustomerId()));
    }

    @Test
    void shouldRejectMissingCustomer() {
        when(customerMapper.selectById(2020000000000000999L)).thenReturn(null);

        BizException error = assertThrows(BizException.class, () -> service.getDetailById(2020000000000000999L));

        assertTrue(error.getMessage().contains("数据不存在"));
    }

    /** 构造一条客户实体；creditLimit 按数据库口径存 100 倍整数。 */
    private Customer customer(Long id) {
        return new Customer()
                .setId(id)
                .setCustomerCode("C001")
                .setCustomerName("上海林间便利连锁")
                .setContactName("秦夏")
                .setContactPhone("021-7728-2001")
                .setAddress("上海市浦东新区张江镇")
                .setCreditLimit(18_000_000L)
                .setStatus(1)
                .setUpdatedById(1900000000000000001L)
                .setUpdatedByName("系统管理员");
    }
}