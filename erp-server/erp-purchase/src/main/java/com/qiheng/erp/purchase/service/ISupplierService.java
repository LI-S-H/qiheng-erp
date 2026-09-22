package com.qiheng.erp.purchase.service;

import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierBatchDeleteDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierBatchStatusDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierCreateDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierPageDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierUpdateDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierServiceScoreDto;
import com.qiheng.erp.purchase.domain.supplier.entity.Supplier;
import com.qiheng.erp.purchase.domain.supplier.vo.SupplierBatchFailure;
import com.qiheng.erp.purchase.domain.supplier.vo.SupplierVo;
import com.qiheng.erp.purchase.domain.supplier.vo.SupplierSummaryVo;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 * 供应商表 服务类
 * </p>
 *
 * @author Li
 * @since 2026-07-29
 */
public interface ISupplierService extends IService<Supplier> {

    /**
     * 供应商分页查询
     * @param dto 分页查询参数DTO
     * @return 分页查询结果VO
     */
    PageResult<SupplierVo> page(SupplierPageDto dto);

    SupplierSummaryVo summary(SupplierPageDto dto);

    SupplierVo detail(Long supplierId);

    /**
     * 新增供应商
     * @param dto 新增供应商请求DTO
     * @return 供应商VO
     */
    SupplierVo create(SupplierCreateDto dto);

    /**
     * 编辑供应商（乐观锁，供应商名称变更时同步供货产品快照）
     * @param supplierId 供应商ID
     * @param dto 编辑供应商请求DTO
     * @return 供应商VO
     */
    SupplierVo update(Long supplierId, SupplierUpdateDto dto);

    /** 人工调整服务分；初始建档不调用本方法。 */
    SupplierVo updateServiceScore(Long supplierId, SupplierServiceScoreDto dto);

    /**
     * 批量修改供应商状态
     * @param dto 批量状态更新请求DTO
     */
    void batchUpdateStatus(SupplierBatchStatusDto dto);

    /**
     * 批量删除供应商（逻辑删除，最佳努力模式）
     * @param dto 批量删除请求DTO
     * @return 失败明细；空列表表示全部成功
     */
    List<SupplierBatchFailure> batchDelete(SupplierBatchDeleteDto dto);
}
