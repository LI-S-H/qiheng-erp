package com.qiheng.erp.warehouse.service;

import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.warehouse.domain.warehouse.dto.WarehouseBatchDeleteDto;
import com.qiheng.erp.warehouse.domain.warehouse.dto.WarehouseBatchStatusDto;
import com.qiheng.erp.warehouse.domain.warehouse.dto.WarehousePageDto;
import com.qiheng.erp.warehouse.domain.warehouse.dto.WarehouseStatusDto;
import com.qiheng.erp.warehouse.domain.warehouse.entity.Warehouse;
import com.qiheng.erp.warehouse.domain.warehouse.vo.WarehouseVo;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.Map;

/**
 * <p>
 * 仓库表 服务类
 * </p>
 *
 * @author Li
 * @since 2026-07-16
 */
public interface IWarehouseService extends IService<Warehouse> {

    /**
     * 分页查询仓库
     * @param dto 分页查询参数
     * @return 分页结果集
     */
    PageResult<WarehouseVo> page(WarehousePageDto dto);

    /**
     * 仓库详情查询
     * @param id 仓库ID
     * @return 仓库VO
     */
    WarehouseVo getDetailById(Long id);

    /**
     * 仓库新增
     * @param warehouse 仓库实体
     * @return 仓库VO
     */
    WarehouseVo add(Warehouse warehouse);

    /**
     * 批量更新仓库状态
     * @param dto 批量更新仓库状态参数DTO
     * @return 失败的仓库信息：key=仓库编码，value=失败原因；空 map 表示全部成功
     */
    Map<String, String> updateBatchStatus(WarehouseBatchStatusDto dto);

    /**
     * 更新仓库状态
     * @param warehouseId 仓库ID
     * @param dto 状态更新参数
     */
    void updateStatus(Long warehouseId, WarehouseStatusDto dto);

    /**
     * 批量删除仓库（逻辑删除）
     * @param dto 批量删除参数（含 warehouseIds 和 versionByWarehouseId）
     * @return 失败的仓库信息：key=仓库ID，value=失败原因；空 map 表示全部成功
     */
    Map<String, String> batchDelete(WarehouseBatchDeleteDto dto);

    /**
     * 仓库更新（乐观锁实现）
     * @param warehouse 仓库实体（需包含 id 和 version）
     * @return 仓库VO
     */
    WarehouseVo update(Warehouse warehouse);
}