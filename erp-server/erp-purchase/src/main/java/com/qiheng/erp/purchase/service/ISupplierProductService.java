package com.qiheng.erp.purchase.service;

import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.purchase.domain.supplierproduct.dto.SupplierProductBatchDeleteDto;
import com.qiheng.erp.purchase.domain.supplierproduct.dto.SupplierProductBatchStatusDto;
import com.qiheng.erp.purchase.domain.supplierproduct.dto.SupplierProductCreateDto;
import com.qiheng.erp.purchase.domain.supplierproduct.dto.SupplierProductPageDto;
import com.qiheng.erp.purchase.domain.supplierproduct.entity.SupplierProduct;
import com.qiheng.erp.purchase.domain.supplierproduct.vo.SupplierProductVo;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.Map;

/**
 * <p>
 * 供应商供货产品表 服务类
 * </p>
 *
 * @author Li
 * @since 2026-07-29
 */
public interface ISupplierProductService extends IService<SupplierProduct> {

    /**
     * 供货产品分页查询
     * @param dto 分页查询参数DTO
     * @return 分页查询结果VO
     */
    PageResult<SupplierProductVo> page(SupplierProductPageDto dto);

    /**
     * 新增供货产品（后端保存供应商和产品快照）
     * @param dto 新增供货产品请求DTO
     * @return 供货产品VO
     */
    SupplierProductVo create(SupplierProductCreateDto dto);

    /**
     * 编辑供货产品（重新校验供应商和产品，保存快照并校验唯一性）
     * @param supplierProductId 供货产品ID
     * @param dto 编辑供货产品请求DTO
     * @return 供货产品VO
     */
    SupplierProductVo update(Long supplierProductId, SupplierProductCreateDto dto);

    /**
     * 批量修改供货产品状态（最佳努力模式，乐观锁实现）
     * @param dto 批量状态修改请求DTO
     * @return 失败的供货产品信息：key=供货产品ID，value=失败原因；空 map 表示全部成功
     */
    Map<String, String> batchUpdateStatus(SupplierProductBatchStatusDto dto);

    /**
     * 批量删除供货产品（逻辑删除，最佳努力模式，乐观锁实现）
     * @param dto 批量删除请求DTO
     * @return 失败的供货产品信息：key=供货产品ID，value=失败原因；空 map 表示全部成功
     */
    Map<String, String> batchDelete(SupplierProductBatchDeleteDto dto);
}
