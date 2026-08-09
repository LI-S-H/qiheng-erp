package com.qiheng.erp.sales.service.impl;

import com.qiheng.erp.sales.domain.customer.entity.Customer;
import com.qiheng.erp.sales.mapper.CustomerMapper;
import com.qiheng.erp.sales.service.ICustomerService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 客户表 服务实现类
 * </p>
 *
 * @author Li
 * @since 2026-08-10
 */
@Service
public class CustomerServiceImpl extends ServiceImpl<CustomerMapper, Customer> implements ICustomerService {

}
