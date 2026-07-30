package com.qiheng.erp.warehouse.service.impl;

import cn.hutool.core.lang.Validator;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.warehouse.domain.warehouse.dto.WarehouseBatchDeleteDto;
import com.qiheng.erp.warehouse.domain.warehouse.dto.WarehouseBatchStatusDto;
import com.qiheng.erp.warehouse.domain.warehouse.dto.WarehousePageDto;
import com.qiheng.erp.warehouse.domain.warehouse.dto.WarehouseStatusDto;
import com.qiheng.erp.warehouse.domain.warehouse.entity.Warehouse;
import com.qiheng.erp.warehouse.domain.warehousestock.entity.WarehouseStock;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBill;
import com.qiheng.erp.warehouse.domain.outbound.entity.OutboundBill;
import com.qiheng.erp.warehouse.domain.stockbill.entity.StockBill;
import com.qiheng.erp.warehouse.domain.stockbill.enums.StockBillStatus;
import com.qiheng.erp.warehouse.domain.warehouse.vo.WarehouseVo;
import com.qiheng.erp.warehouse.mapper.WarehouseMapper;
import com.qiheng.erp.warehouse.service.IInboundBillService;
import com.qiheng.erp.warehouse.service.IOutboundBillService;
import com.qiheng.erp.warehouse.service.IStockBillService;
import com.qiheng.erp.warehouse.service.IWarehouseService;
import com.qiheng.erp.warehouse.service.IWarehouseStockService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <p>
 * 仓库表 服务实现类
 * </p>
 *
 * @author Li
 * @since 2026-07-16
 */
@Service
public class WarehouseServiceImpl extends ServiceImpl<WarehouseMapper, Warehouse> implements IWarehouseService {

    @Autowired
    private WarehouseMapper warehouseMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IWarehouseStockService warehouseStockService;

    @Autowired
    private IInboundBillService inboundBillService;

    @Autowired
    private IOutboundBillService outboundBillService;

    @Autowired
    private IStockBillService stockBillService;

    /**
     * 分页查询仓库
     * @param dto 分页查询参数
     * @return 分页结果集
     */
    @Override
    public PageResult<WarehouseVo> page(WarehousePageDto dto) {
        LambdaQueryWrapper<Warehouse> wrapper = new LambdaQueryWrapper<Warehouse>()
                .like(StrUtil.isNotBlank(dto.getWarehouseCode()), Warehouse::getWarehouseCode, dto.getWarehouseCode())
                .like(StrUtil.isNotBlank(dto.getWarehouseName()), Warehouse::getWarehouseName, dto.getWarehouseName())
                .like(StrUtil.isNotBlank(dto.getContactName()), Warehouse::getContactName, dto.getContactName())
                .like(StrUtil.isNotBlank(dto.getContactPhone()), Warehouse::getContactPhone, dto.getContactPhone())
                .eq(dto.getStatus() != null, Warehouse::getStatus, dto.getStatus())
                .orderByDesc(Warehouse::getCreateTime);

        Page<Warehouse> page = dto.toPage();
        Page<Warehouse> result = warehouseMapper.selectPage(page, wrapper);

        List<WarehouseVo> voList = result.getRecords().stream().map(this::getWarehouseVo).collect(Collectors.toList());
        return PageResult.of(voList, (int) result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    /**
     * 根据ID查询仓库详情
     * @param id 仓库ID
     * @return 仓库VO
     */
    @Override
    public WarehouseVo getDetailById(Long id) {
        Warehouse warehouse = warehouseMapper.selectById(id);
        if (warehouse == null) {
            return null;
        }
        return getWarehouseVo(warehouse);
    }

    /**
     * 仓库新增
     * @param warehouse 仓库实体
     * @return 仓库VO
     */
    @Override
    public WarehouseVo add(Warehouse warehouse) {
        warehouse.setWarehouseCode(generateWarehouseCode());
        if (warehouse.getStatus() == null) {
            warehouse.setStatus(1);
        }
        if (!Validator.isMobile(warehouse.getContactPhone())) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "联系电话格式不正确");
        }
        warehouseMapper.insert(warehouse);
        return getDetailById(warehouse.getId());
    }

    /**
     * 生成仓库编码
     * @return 仓库编码
     */
    private String generateWarehouseCode() {
        Long seq = stringRedisTemplate.opsForValue().increment("warehouse:code");
        return "WH" + String.format("%06d", seq);
    }

    /**
     * 批量更新仓库状态（乐观锁实现）
     * @param dto 批量更新仓库状态参数DTO
     * @return 失败的仓库信息：key=仓库ID，value=失败原因；空 map 表示全部成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, String> updateBatchStatus(WarehouseBatchStatusDto dto) {
        Map<String, String> failures = new LinkedHashMap<>();
        // 批量更新仓库状态，使用乐观锁实现
        for (String warehouseId : dto.getWarehouseIds()) {
            Integer expectedVersion = dto.getVersionByWarehouseId().get(warehouseId);
            if (expectedVersion == null) {
                failures.put(warehouseId, "未找到版本号");
                continue;
            }
            Long id = Long.parseLong(warehouseId);
            if (Integer.valueOf(0).equals(dto.getStatus())) {
                ensureCanDisable(id);
            }
            Warehouse warehouse = new Warehouse();
            warehouse.setId(id);
            warehouse.setStatus(dto.getStatus());
            warehouse.setVersion(expectedVersion);
            int rows = warehouseMapper.updateById(warehouse);
            if (rows == 0) {
                failures.put(warehouseId, "仓库不存在或数据已发生变化，请刷新后重试");
            }
        }

        return failures;
    }

    /**
     * 更新仓库状态（乐观锁实现）
     * @param warehouseId 仓库ID
     * @param dto 状态更新参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long warehouseId, WarehouseStatusDto dto) {
        if (Integer.valueOf(0).equals(dto.getStatus())) {
            ensureCanDisable(warehouseId);
        }
        Warehouse warehouse = new Warehouse();
        warehouse.setId(warehouseId);
        warehouse.setStatus(dto.getStatus());
        warehouse.setVersion(dto.getVersion());
        int rows = warehouseMapper.updateById(warehouse);
        if (rows == 0) {
            throw new BizException(
                    ErrorCode.OPERATION_FAILED.getCode(),
                    "仓库不存在或数据已发生变化，请刷新后重试");
        }
    }

    /**
     * 批量删除仓库（逻辑删除，最佳努力模式，乐观锁实现）
     * @param dto 批量删除参数
     * @return 失败的仓库信息：key=仓库ID，value=失败原因；空 map 表示全部成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, String> batchDelete(WarehouseBatchDeleteDto dto) {
        Map<String, String> failures = new LinkedHashMap<>();
        for (String warehouseIdStr : dto.getWarehouseIds()) {
            Long warehouseId = Long.parseLong(warehouseIdStr);
            Integer expectedVersion = dto.getVersionByWarehouseId().get(warehouseIdStr);
            if (expectedVersion == null) {
                failures.put(warehouseIdStr, "未找到版本号");
                continue;
            }
            ensureCanDelete(warehouseId);
            int rows = warehouseMapper.deleteByIdWithVersion(warehouseId, expectedVersion);
            if (rows == 0) {
                failures.put(warehouseIdStr, "仓库不存在或数据已发生变化，请刷新后重试");
            }
        }
        return failures;
    }

    /**
     * 仓库更新（乐观锁实现）
     * @param warehouse 仓库实体（需包含 id 和 version）
     * @return 仓库VO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public WarehouseVo update(Warehouse warehouse) {
        warehouse.setWarehouseCode(null);
        if (warehouse.getVersion() == null) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "版本号不能为空");
        }
        if (!Validator.isMobile(warehouse.getContactPhone())) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "联系电话格式不正确");
        }
        int rows = warehouseMapper.updateById(warehouse);
        if (rows == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                    "仓库不存在或数据已发生变化，请刷新后重试");
        }
        Warehouse updatedWarehouse = warehouseMapper.selectById(warehouse.getId());
        // 库存余额中的仓库名称属于当前态冗余字段，需与仓库主数据保持一致；
        // 已生成的业务单据和库存流水保留创建时快照，避免改名后篡改历史语义。
        warehouseStockService.update(new LambdaUpdateWrapper<WarehouseStock>()
                .eq(WarehouseStock::getWarehouseId, warehouse.getId())
                .set(WarehouseStock::getWarehouseName, updatedWarehouse.getWarehouseName()));
        return getWarehouseVo(updatedWarehouse);
    }

    private WarehouseVo getWarehouseVo(Warehouse w) {
        WarehouseVo vo = new WarehouseVo();
        vo.setWarehouseId(w.getId());
        vo.setWarehouseCode(w.getWarehouseCode());
        vo.setWarehouseName(w.getWarehouseName());
        vo.setContactName(w.getContactName());
        vo.setContactPhone(w.getContactPhone());
        vo.setAddress(w.getAddress());
        vo.setStatus(w.getStatus());
        vo.setVersion(w.getVersion());
        vo.setRemark(w.getRemark());
        vo.setCreateTime(w.getCreateTime());
        vo.setUpdateTime(w.getUpdateTime());
        return vo;
    }

    /**
     * 仓库停用会阻止后续建单，因此必须先确保没有仍在流转的业务单，也没有可用或已锁定库存。
     */
    private void ensureCanDisable(Long warehouseId) {
        boolean hasStockBalance = warehouseStockService.exists(new LambdaQueryWrapper<WarehouseStock>()
                .eq(WarehouseStock::getWarehouseId, warehouseId)
                .and(wrapper -> wrapper.gt(WarehouseStock::getStockQty, 0)
                        .or().gt(WarehouseStock::getLockedQty, 0)));
        if (hasStockBalance) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "仓库存在可用或锁定库存，不能停用");
        }
        if (hasUnfinishedBills(warehouseId)) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "仓库存在草稿或待确认出入库单，不能停用");
        }
    }

    /**
     * 删除仓库会破坏库存追溯链路；零库存记录、历史工作单和库存流水同样属于不可删除的业务事实。
     */
    private void ensureCanDelete(Long warehouseId) {
        if (warehouseStockService.exists(new LambdaQueryWrapper<WarehouseStock>()
                .eq(WarehouseStock::getWarehouseId, warehouseId))) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "仓库存在库存记录，不能删除");
        }
        if (inboundBillService.exists(new LambdaQueryWrapper<InboundBill>()
                .eq(InboundBill::getWarehouseId, warehouseId))
                || outboundBillService.exists(new LambdaQueryWrapper<OutboundBill>()
                .eq(OutboundBill::getWarehouseId, warehouseId))
                || stockBillService.exists(new LambdaQueryWrapper<StockBill>()
                .eq(StockBill::getWarehouseId, warehouseId))) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "仓库存在历史单据或库存流水，不能删除");
        }
    }

    private boolean hasUnfinishedBills(Long warehouseId) {
        return inboundBillService.exists(new LambdaQueryWrapper<InboundBill>()
                .eq(InboundBill::getWarehouseId, warehouseId)
                .in(InboundBill::getStatus, StockBillStatus.DRAFT.name(), StockBillStatus.PENDING_CONFIRM.name()))
                || outboundBillService.exists(new LambdaQueryWrapper<OutboundBill>()
                .eq(OutboundBill::getWarehouseId, warehouseId)
                .in(OutboundBill::getStatus, StockBillStatus.DRAFT.name(), StockBillStatus.PENDING_CONFIRM.name()));
    }


}
