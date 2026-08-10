package com.qiheng.erp.sales.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.sales.domain.customer.dto.CustomerBatchDeleteDto;
import com.qiheng.erp.sales.domain.customer.dto.CustomerBatchStatusDto;
import com.qiheng.erp.sales.domain.customer.dto.CustomerCreateDto;
import com.qiheng.erp.sales.domain.customer.dto.CustomerPageDto;
import com.qiheng.erp.sales.domain.customer.dto.CustomerUpdateDto;
import com.qiheng.erp.sales.domain.customer.entity.Customer;
import com.qiheng.erp.sales.domain.customer.vo.CustomerVo;

import java.util.Map;

/**
 * <p>
 * 客户表 服务类
 * </p>
 *
 * @author Li
 * @since 2026-08-10
 */
public interface ICustomerService extends IService<Customer> {

    /**
     * 客户分页查询
     * @param dto 分页查询参数
     * @return 分页结果集
     */
    PageResult<CustomerVo> page(CustomerPageDto dto);

    /**
     * 新增客户（后端生成客户编码，信用额度按 100 倍入库）
     * @param dto 新增客户请求 DTO
     * @return 新增后的客户 VO
     */
    CustomerVo create(CustomerCreateDto dto);

    /**
     * 编辑客户（乐观锁；客户名称变更时同步 DRAFT 状态销售订单的客户快照）
     * @param customerId 客户 ID
     * @param dto 编辑客户请求 DTO
     * @return 编辑后的客户 VO
     */
    CustomerVo update(Long customerId, CustomerUpdateDto dto);

    /**
     * 批量修改客户状态（最佳努力模式，启用跳过校验，停用校验未完成销售单/退货单）
     * @param dto 批量状态更新请求 DTO
     * @return 失败的客户信息：key=客户ID，value=失败原因；空 map 表示全部成功
     */
    Map<String, String> batchUpdateStatus(CustomerBatchStatusDto dto);

    /**
     * 批量逻辑删除客户（最佳努力模式，乐观锁；存在销售单或销售退货单时整条失败）
     * @param dto 批量删除请求 DTO
     * @return 失败的客户信息：key=客户ID，value=失败原因；空 map 表示全部成功
     */
    Map<String, String> batchDelete(CustomerBatchDeleteDto dto);
}