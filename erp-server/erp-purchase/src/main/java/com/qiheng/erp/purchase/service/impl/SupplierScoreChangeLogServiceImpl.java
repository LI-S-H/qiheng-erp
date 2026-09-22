package com.qiheng.erp.purchase.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.purchase.domain.supplierproduct.entity.SupplierProduct;
import com.qiheng.erp.purchase.domain.supplierscore.dto.SupplierScoreChangeLogPageDto;
import com.qiheng.erp.purchase.domain.supplierscore.entity.SupplierScoreChangeLog;
import com.qiheng.erp.purchase.domain.supplierscore.vo.SupplierScoreChangeLogVo;
import com.qiheng.erp.purchase.mapper.SupplierMapper;
import com.qiheng.erp.purchase.mapper.SupplierProductMapper;
import com.qiheng.erp.purchase.mapper.SupplierScoreChangeLogMapper;
import com.qiheng.erp.purchase.service.ISupplierScoreChangeLogService;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * <p>
 * 供应商 / 供货产品 评分变更日志服务实现
 * </p>
 * <p>
 * 第 3 期评分引擎接入 RocketMQ 后,本服务会承接按 supplier_id / supplier_product_id
 * 触发重算并写日志的同事务流程,因此独立于 SupplierServiceImpl / SupplierProductServiceImpl。
 * </p>
 *
 * @author Li
 * @since 2026-09-21
 */
@Service
public class SupplierScoreChangeLogServiceImpl extends ServiceImpl<SupplierScoreChangeLogMapper, SupplierScoreChangeLog>
        implements ISupplierScoreChangeLogService {
    @Autowired
    private SupplierProductMapper supplierProductMapper;
    @Autowired
    private SupplierScoreChangeLogMapper supplierScoreChangeLogMapper;
    @Autowired
    private SupplierMapper supplierMapper;

    /**
     * 分页查询供货产品评分变更日志
     */
    @Override
    public PageResult<SupplierScoreChangeLogVo> pageScoreChangeLogs(Long supplierProductId,
                                                                   SupplierScoreChangeLogPageDto dto) {
        if (supplierProductMapper.selectById(supplierProductId) == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
        Page<SupplierScoreChangeLog> result = supplierScoreChangeLogMapper.selectPage(dto.toPage(),
                new LambdaQueryWrapper<SupplierScoreChangeLog>()
                        .eq(SupplierScoreChangeLog::getSupplierProductId, supplierProductId)
                        .orderByDesc(SupplierScoreChangeLog::getCreateTime)
                        .orderByDesc(SupplierScoreChangeLog::getId));
        return PageResult.of(result.getRecords().stream().map(this::toScoreChangeLogVo).toList(),
                (int) result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    /**
     * 分页查询供应商评分变更日志
     */
    @Override
    public PageResult<SupplierScoreChangeLogVo> pageScoreChangeLogsBySupplier(Long supplierId,
                                                                              SupplierScoreChangeLogPageDto dto) {
        if (supplierMapper.selectById(supplierId) == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
        Page<SupplierScoreChangeLog> page = dto.toPage();
        Page<SupplierScoreChangeLog> result = supplierScoreChangeLogMapper.selectPage(page,
                new LambdaQueryWrapper<SupplierScoreChangeLog>()
                        .eq(SupplierScoreChangeLog::getSupplierId, supplierId)
                        .orderByDesc(SupplierScoreChangeLog::getCreateTime)
                        .orderByDesc(SupplierScoreChangeLog::getId));
        return PageResult.of(result.getRecords().stream().map(this::toScoreChangeLogVo).toList(),
                (int) result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    /**
     * 写入供应商服务分变更日志
     * <p>显式声明 REQUIRED：与 supplier.service_score 更新保持在同一事务内,避免
     * 后续被事务外调用导致分数已变更但日志未写入的数据漂移。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SupplierScoreChangeLog writeServiceScoreLog(Long supplierId, Integer metricScoreBefore,
                                                       Integer metricScoreAfter, String reason, LoginUser operator) {
        SupplierScoreChangeLog log = new SupplierScoreChangeLog();
        log.setChangeKey(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        log.setBatchNo("SERVICE-" + System.currentTimeMillis());
        log.setRuleVersion("P1-MANUAL");
        log.setSupplierId(supplierId);
        log.setMetricType("SERVICE");
        log.setMetricScoreBefore(metricScoreBefore);
        log.setMetricScoreAfter(metricScoreAfter);
        log.setTriggerType("SERVICE");
        log.setRelatedBusinessNo("");
        log.setOperatorId(operator.getUserId());
        log.setOperatorName(operator.getRealName());
        log.setReason(reason);
        supplierScoreChangeLogMapper.insert(log);
        return log;
    }

    /**
     * 转换评分变更日志实体为VO
     */
    private SupplierScoreChangeLogVo toScoreChangeLogVo(SupplierScoreChangeLog entity) {
        SupplierScoreChangeLogVo vo = new SupplierScoreChangeLogVo();
        vo.setScoreChangeLogId(entity.getId());
        vo.setSupplierId(entity.getSupplierId());
        vo.setSupplierProductId(entity.getSupplierProductId());
        vo.setMetricType(entity.getMetricType());
        vo.setMetricScoreBefore(QtyUtil.toDecimal(entity.getMetricScoreBefore()));
        vo.setMetricScoreAfter(QtyUtil.toDecimal(entity.getMetricScoreAfter()));
        vo.setProductRecommendScoreBefore(QtyUtil.toDecimal(entity.getProductRecommendScoreBefore()));
        vo.setProductRecommendScoreAfter(QtyUtil.toDecimal(entity.getProductRecommendScoreAfter()));
        vo.setSupplierOverallScoreBefore(QtyUtil.toDecimal(entity.getSupplierOverallScoreBefore()));
        vo.setSupplierOverallScoreAfter(QtyUtil.toDecimal(entity.getSupplierOverallScoreAfter()));
        vo.setTriggerType(entity.getTriggerType());
        vo.setRelatedBusinessId(entity.getRelatedBusinessId());
        vo.setRelatedBusinessNo(entity.getRelatedBusinessNo());
        vo.setOperatorId(entity.getOperatorId());
        vo.setOperatorName(entity.getOperatorName());
        vo.setReason(entity.getReason());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }
}