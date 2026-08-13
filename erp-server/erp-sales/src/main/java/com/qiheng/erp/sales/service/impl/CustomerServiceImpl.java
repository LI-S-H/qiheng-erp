package com.qiheng.erp.sales.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.util.CodeGen;
import com.qiheng.erp.common.util.IdUtil;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.returnorder.domain.entity.ReturnOrder;
import com.qiheng.erp.returnorder.domain.enums.ReturnStatus;
import com.qiheng.erp.returnorder.domain.port.ReturnType;
import com.qiheng.erp.returnorder.mapper.ReturnOrderMapper;
import com.qiheng.erp.sales.domain.customer.dto.CustomerBatchDeleteDto;
import com.qiheng.erp.sales.domain.customer.dto.CustomerBatchStatusDto;
import com.qiheng.erp.sales.domain.customer.dto.CustomerCreateDto;
import com.qiheng.erp.sales.domain.customer.dto.CustomerPageDto;
import com.qiheng.erp.sales.domain.customer.dto.CustomerUpdateDto;
import com.qiheng.erp.sales.domain.customer.entity.Customer;
import com.qiheng.erp.sales.domain.customer.vo.CustomerVo;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.qiheng.erp.sales.domain.salesorder.enums.SalesOrderStatus;
import com.qiheng.erp.sales.mapper.CustomerMapper;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import com.qiheng.erp.sales.service.ICustomerService;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    @Autowired
    private CustomerMapper customerMapper;
    @Autowired
    private ReturnOrderMapper returnOrderMapper;
    @Autowired
    private SalesOrderMapper salesOrderMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    /**
     * 客户分页查询
     * @param dto 分页查询参数
     * @return 分页结果集
     */
    @Override
    public PageResult<CustomerVo> page(CustomerPageDto dto) {
        LambdaQueryWrapper<Customer> wrapper = new LambdaQueryWrapper<Customer>()
                .like(StrUtil.isNotBlank(dto.getCustomerCode()), Customer::getCustomerCode, dto.getCustomerCode())
                .like(StrUtil.isNotBlank(dto.getCustomerName()), Customer::getCustomerName, dto.getCustomerName())
                .like(StrUtil.isNotBlank(dto.getContactName()), Customer::getContactName, dto.getContactName())
                .eq(dto.getStatus() != null, Customer::getStatus, dto.getStatus())
                .orderByDesc(Customer::getCreateTime);
        Page<Customer> result = customerMapper.selectPage(dto.toPage(), wrapper);
        return PageResult.of(
                result.getRecords().stream().map(this::toVo).toList(),
                (int) result.getTotal(),
                (int) result.getCurrent(),
                (int) result.getSize()
        );
    }

    /**
     * 新增客户（后端生成客户编码，信用额度按 100 倍入库）
     * @param dto 新增客户请求 DTO
     * @return 新增后的客户 VO
     */
    @Override
    public CustomerVo create(CustomerCreateDto dto) {
        LoginUser currentUser = UserContext.requireCurrentUser();
        Customer entity = BeanUtil.copyProperties(dto, Customer.class);
        entity.setCustomerCode(CodeGen.next(stringRedisTemplate, "customer:code", "C", 4));
        entity.setCreditLimit(QtyUtil.toStored(dto.getCreditLimit()));
        entity.setUpdatedById(currentUser.getUserId());
        entity.setUpdatedByName(currentUser.getRealName());
        customerMapper.insert(entity);
        return toVo(customerMapper.selectById(entity.getId()));
    }

    /**
     * 编辑客户（乐观锁；客户名称变更时同步 DRAFT 状态销售订单的客户快照）
     * @param customerId 客户 ID
     * @param dto 编辑客户请求 DTO
     * @return 编辑后的客户 VO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CustomerVo update(Long customerId, CustomerUpdateDto dto) {
        Customer existing = customerMapper.selectById(customerId);
        if (existing == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
        LoginUser currentUser = UserContext.requireCurrentUser();
        String oldCustomerName = existing.getCustomerName();
        Customer entity = BeanUtil.copyProperties(dto, Customer.class);
        entity.setId(customerId);
        entity.setCustomerCode(null);
        entity.setCreditLimit(QtyUtil.toStored(dto.getCreditLimit()));
        entity.setUpdatedById(currentUser.getUserId());
        entity.setUpdatedByName(currentUser.getRealName());
        int rows = customerMapper.updateById(entity);
        if (rows == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                    "客户不存在或数据已发生变化，请刷新后重试");
        }
        if (!oldCustomerName.equals(dto.getCustomerName())) {
            salesOrderMapper.update(null, new LambdaUpdateWrapper<SalesOrder>()
                    .eq(SalesOrder::getCustomerId, customerId)
                    .eq(SalesOrder::getStatus, SalesOrderStatus.DRAFT.name())
                    .set(SalesOrder::getCustomerName, dto.getCustomerName()));
        }
        return toVo(customerMapper.selectById(customerId));
    }

    /**
     * 批量修改客户状态（最佳努力模式，乐观锁；停用时校验未完成退货单和销售单）
     * @param dto 批量状态更新请求 DTO
     * @return 失败的客户信息：key=客户ID，value=失败原因；空 map 表示全部成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, String> batchUpdateStatus(CustomerBatchStatusDto dto) {
        // 1. 转换为 Long 列表
        List<Long> customerIds = IdUtil.parseRequiredLongIds(dto.getCustomerIds(), "客户ID");
        LoginUser currentUser = UserContext.requireCurrentUser();
        // 2. 校验版本号
        Map<String, String> failures = new LinkedHashMap<>();
        for (int index = 0; index < dto.getCustomerIds().size(); index++) {
            String customerId = dto.getCustomerIds().get(index);
            Integer expectedVersion = dto.getVersionByCustomerId().get(customerId);
            if (expectedVersion == null) {
                failures.put(customerId, "未找到版本号");
                continue;
            }
            Long id = customerIds.get(index);
            // 如果停用，校验是否存在完成退货单或销售单
            if (Integer.valueOf(0).equals(dto.getStatus())) {
                try {
                    ensureCanDisable(id);
                } catch (BizException exception) {
                    failures.put(customerId, exception.getMessage());
                    continue;
                }
            }
            Customer entity = new Customer();
            entity.setId(id);
            entity.setStatus(dto.getStatus());
            entity.setVersion(expectedVersion);
            entity.setUpdatedById(currentUser.getUserId());
            entity.setUpdatedByName(currentUser.getRealName());
            int rows = customerMapper.updateById(entity);
            if (rows == 0) {
                failures.put(customerId, "客户不存在或数据已发生变化，请刷新后重试");
            }
        }
        return failures;
    }

    /**
     * 批量逻辑删除客户（最佳努力模式，乐观锁；存在销售单或销售退货单时整条失败）
     * @param dto 批量删除请求 DTO
     * @return 失败的客户信息：key=客户ID，value=失败原因；空 map 表示全部成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, String> batchDelete(CustomerBatchDeleteDto dto) {
        List<Long> customerIds = IdUtil.parseRequiredLongIds(dto.getCustomerIds(), "客户ID");
        Map<String, String> failures = new LinkedHashMap<>();
        for (int index = 0; index < dto.getCustomerIds().size(); index++) {
            // 1. 校验版本号是否存在
            String customerId = dto.getCustomerIds().get(index);
            Integer expectedVersion = dto.getVersionByCustomerId().get(customerId);
            if (expectedVersion == null) {
                failures.put(customerId, "未找到版本号");
                continue;
            }
            // 2. 校验是否存在退货单或销售单
            Long id = customerIds.get(index);
            try {
                ensureCanDelete(id);
            } catch (BizException exception) {
                failures.put(customerId, exception.getMessage());
                continue;
            }
            int rows = customerMapper.deleteByIdWithVersion(id, expectedVersion);
            if (rows == 0) {
                failures.put(customerId, "客户不存在或数据已发生变化，请刷新后重试");
            }
        }
        return failures;
    }

    /**
     * 实体转 VO，信用额度从 100 倍存储值还原为业务小数
     */
    private CustomerVo toVo(Customer entity) {
        CustomerVo vo = BeanUtil.copyProperties(entity, CustomerVo.class);
        vo.setCustomerId(entity.getId());
        vo.setCreditLimit(QtyUtil.toDecimal(entity.getCreditLimit()));
        return vo;
    }


    /**
     * 客户停用前置校验：存在未完成退货单或销售单时不允许停用
     */
    private void ensureCanDisable(Long customerId) {
        // 1. 存在未完成销售退货单（状态非 COMPLETED / CANCELLED）
        Long activeReturnCount = returnOrderMapper.selectCount(
                new LambdaQueryWrapper<ReturnOrder>()
                        .eq(ReturnOrder::getPartyId, customerId)
                        .eq(ReturnOrder::getReturnType, ReturnType.SALES_RETURN.name())
                        .notIn(ReturnOrder::getStatus,
                                List.of(ReturnStatus.COMPLETED.name(), ReturnStatus.CANCELLED.name()))
        );
        if (activeReturnCount > 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                    "客户存在未完成销售退货单，不能停用");
        }
        // 2. 存在未完成销售单（状态非 OUTBOUND_DONE / CANCELLED）
        Long activeSalesCount = salesOrderMapper.selectCount(
                new LambdaQueryWrapper<SalesOrder>()
                        .eq(SalesOrder::getCustomerId, customerId)
                        .notIn(SalesOrder::getStatus,
                                List.of(SalesOrderStatus.OUTBOUND_DONE.name(), SalesOrderStatus.CANCELLED.name()))
        );
        if (activeSalesCount > 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                    "客户存在未完成销售订单，不能停用");
        }
    }

    /**
     * 客户删除前置校验：存在任何状态的退货单或销售单时不允许删除（避免破坏销售追溯链路）
     */
    private void ensureCanDelete(Long customerId) {
        // 1. 存在任何状态的销售退货单
        Long returnCount = returnOrderMapper.selectCount(
                new LambdaQueryWrapper<ReturnOrder>()
                        .eq(ReturnOrder::getPartyId, customerId)
                        .eq(ReturnOrder::getReturnType, ReturnType.SALES_RETURN.name())
        );
        if (returnCount > 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                    "客户存在销售退货单，不能删除");
        }
        // 2. 存在任何状态的销售单
        Long salesCount = salesOrderMapper.selectCount(
                new LambdaQueryWrapper<SalesOrder>()
                        .eq(SalesOrder::getCustomerId, customerId)
        );
        if (salesCount > 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                    "客户存在销售订单，不能删除");
        }
    }
}
