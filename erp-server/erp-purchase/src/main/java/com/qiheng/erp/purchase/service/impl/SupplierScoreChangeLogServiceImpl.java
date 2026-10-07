package com.qiheng.erp.purchase.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.util.HashUtil;
import com.qiheng.erp.common.util.IdUtil;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreChangeBatchCommand;
import com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreChangeLogEntry;
import com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreChangeSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.qiheng.erp.purchase.domain.supplierscore.dto.SupplierScoreChangeLogPageDto;
import com.qiheng.erp.purchase.domain.supplierscore.entity.SupplierScoreChangeLog;
import com.qiheng.erp.purchase.domain.supplierscore.enums.OperatorType;
import com.qiheng.erp.purchase.domain.supplierscore.vo.SupplierScoreChangeLogVo;
import com.qiheng.erp.purchase.mapper.SupplierScoreChangeLogMapper;
import com.qiheng.erp.purchase.service.ISupplierScoreChangeLogService;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.math.BigInteger;

/**
 * <p>
 * 供应商 / 供货产品 评分变更日志服务实现
 * </p>
 *
 * <p>本服务承担两类职责:</p>
 * <ul>
 *   <li>统一分页查询评分变更日志，供应商、供货关系及其他条件按 AND 组合</li>
 *   <li>写入入口 {@link #appendBatch(ScoreChangeBatchCommand)}:由 SupplierScoreRecalculateService 在重算完成后
 *       调用,在同一业务事务内写入。每条日志通过 changeKey = SHA-256(batchNo + supplierId + metricType + supplierProductId)
 *       走数据库 UNIQUE INDEX 实现幂等,MQ 重投 / Consumer 重试下不重复写入</li>
 * </ul>
 *
 * <p>字段语义:</p>
 * <ul>
 *   <li>operatorType = USER:人工操作(服务分 / 报价 / 参考价),operatorId/operatorName 填实际用户</li>
 *   <li>operatorType = SYSTEM:系统任务(MQ Consumer / D1 报价过期 / D2 每日兜底 / 合并重算),
 *       operatorId 为 NULL,operatorName 填场景描述(如 "D2-每日兜底")</li>
 * </ul>
 *
 * @author Li
 * @since 2026-09-21
 */
@Service
public class SupplierScoreChangeLogServiceImpl extends ServiceImpl<SupplierScoreChangeLogMapper, SupplierScoreChangeLog>
        implements ISupplierScoreChangeLogService {

    private static final Pattern BUSINESS_ID_PATTERN = Pattern.compile("[1-9][0-9]{0,18}");

    @Autowired
    private SupplierScoreChangeLogMapper supplierScoreChangeLogMapper;
    @Autowired
    private ObjectMapper objectMapper;
    /**
     * 分页查询评分变更日志，不传 ID 查询全部，同时传两个 ID 时按 AND 筛选。
     *
     * @param dto 分页、供应商、供货产品、指标和时间筛选条件
     * @return 分数已转换为业务小数的评分变更日志分页结果
     * @throws BizException ID 格式错误或开始时间晚于结束时间
     */
    @Override
    public PageResult<SupplierScoreChangeLogVo> pageScoreChangeLogs(SupplierScoreChangeLogPageDto dto) {
        // 1. 校验时间范围
        if (dto.getStartTime() != null && dto.getEndTime() != null
                && dto.getStartTime().isAfter(dto.getEndTime())) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "开始时间不能晚于结束时间");
        }
        // 2. 解析可选的供应商ID和供货产品ID
        Long supplierId = IdUtil.parseOptionalLongId(dto.getSupplierId(), "供应商ID");
        Long supplierProductId = IdUtil.parseOptionalLongId(dto.getSupplierProductId(), "供货产品ID");
        // 3. 构建基础查询条件：供应商ID + 供货产品ID + 按创建时间倒序 + ID倒序
        LambdaQueryWrapper<SupplierScoreChangeLog> wrapper = new LambdaQueryWrapper<SupplierScoreChangeLog>()
                .eq(supplierId != null, SupplierScoreChangeLog::getSupplierId, supplierId)
                .eq(supplierProductId != null, SupplierScoreChangeLog::getSupplierProductId, supplierProductId)
                .orderByDesc(SupplierScoreChangeLog::getCreateTime)
                .orderByDesc(SupplierScoreChangeLog::getId);
        // 4. 追加指标类型、触发类型、批次号、时间区间等筛选条件
        applyFilters(wrapper, dto);
        // 5. 分页查询并转换为VO（分数字段从INT×100转为业务小数）
        Page<SupplierScoreChangeLog> result = supplierScoreChangeLogMapper.selectPage(dto.toPage(), wrapper);
        return PageResult.of(toVoList(result.getRecords()),
                (int) result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    /**
     * 批量写入评分变更日志(同步,业务事务内调用)。
     *
     * <p>由评分重算实现类在重算完成后调用。
     * 每条 entry 自动计算 changeKey = SHA-256(batchNo + supplierId + metricType + supplierProductId),
     * 依赖 DB UNIQUE INDEX uk_supplier_score_change_key 实现幂等,MQ 重投 / Consumer 重试下不重复写入。</p>
     *
     * <p>本方法在写入前做三道防线,避免脏数据落库:</p>
     * <ol>
     *   <li>supplierId 必填:每条日志必须归属一个供应商,缺失直接抛业务异常,不在 DB 留 NULL 行</li>
     *   <li>entry.metricType 必填:缺失抛业务异常,避免 .name() NPE 把整批业务事务连带回滚</li>
     *   <li>指标分、推荐分和综合分均未变化时跳过；仅衍生分变化仍记录日志</li>
     * </ol>
     *
     * <p>changeKey 拼接约定:supplierId 必非空,用 toString() 转字符串;supplierProductId 为 null 时
     * 直接透传给 HashUtil,由其内部统一用 "NULL" 表示。两个字段的 null 表达方式在密钥材料中保持一致。</p>
     *
     * @param command 批量写入入参;为 null 或 entries 为空时直接返回
     * @throws BizException 批次、来源、操作人、分数或文本长度不合法时
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void appendBatch(ScoreChangeBatchCommand command) {
        // 0. 空入参直接返回
        if (command == null || command.getEntries() == null || command.getEntries().isEmpty()) {
            return;
        }
        // 1. 校验批次级必填字段：supplierId、批次号、规则版本、操作人名称、触发类型
        if (command.getSupplierId() == null || command.getSupplierId() <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "批量写入评分日志失败:supplierId 必填");
        }
        requireText(command.getBatchNo(), 64, "批次号");
        requireText(command.getRuleVersion(), 32, "规则版本");
        requireText(command.getOperatorName(), 100, "操作人名称");
        if (command.getTriggerType() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "评分日志触发类型不能为空");
        }
        // 2. 校验操作人身份：USER必须带真实用户ID，SYSTEM必须不带用户ID
        if (OperatorType.USER.name().equals(command.getOperatorType())) {
            if (command.getOperatorId() == null || command.getOperatorId() <= 0) {
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "人工评分日志必须填写真实用户ID");
            }
        } else if (!OperatorType.SYSTEM.name().equals(command.getOperatorType()) || command.getOperatorId() != null) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "系统评分日志必须使用SYSTEM且用户ID为空");
        }
        // 3. 校验原因长度
        if (command.getReason() != null && command.getReason().length() > 500) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "评分日志原因不能超过500个字符");
        }
        // 4. 预序列化来源列表（去重+稳定排序），避免循环内重复序列化
        String sourcesJson = serializeSources(command.getRelatedSources());
        String supplierIdText = command.getSupplierId().toString();
        // 5. 逐条处理日志条目
        for (ScoreChangeLogEntry entry : command.getEntries()) {
            // 5.1 校验条目级必填字段：metricType、supplierProductId
            if (entry == null || entry.getMetricType() == null) {
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "批量写入评分日志失败:metricType 必填");
            }
            if (entry.getSupplierProductId() != null && entry.getSupplierProductId() <= 0) {
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "评分日志供货关系ID必须为正整数");
            }
            // 5.2 校验所有分数字段范围：空或在[0, 10000]
            for (Integer score : new Integer[]{entry.getMetricScoreBefore(), entry.getMetricScoreAfter(),
                    entry.getProductRecommendScoreBefore(), entry.getProductRecommendScoreAfter(),
                    entry.getSupplierOverallScoreBefore(), entry.getSupplierOverallScoreAfter()}) {
                if (score != null && (score < 0 || score > 10000)) {
                    throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "评分日志分数必须为空或在0至10000之间");
                }
            }
            // 5.3 跳过无变化的条目：基础指标、推荐分、综合分均未变时不落库
            if (Objects.equals(entry.getMetricScoreBefore(), entry.getMetricScoreAfter())
                    && Objects.equals(entry.getProductRecommendScoreBefore(), entry.getProductRecommendScoreAfter())
                    && Objects.equals(entry.getSupplierOverallScoreBefore(), entry.getSupplierOverallScoreAfter())) {
                continue;
            }
            // 5.4 计算幂等键 changeKey = SHA-256(batchNo + supplierId + metricType + supplierProductId)
            String changeKey = HashUtil.sha256HexConcat(
                    command.getBatchNo(),
                    supplierIdText,
                    entry.getMetricType().name(),
                    entry.getSupplierProductId() == null ? null : entry.getSupplierProductId().toString()
            );
            // 5.5 写入单条日志，DuplicateKeyException仅允许幂等键冲突（MQ重投/Consumer重试）
            try {
                supplierScoreChangeLogMapper.insert(buildLog(command, entry, changeKey, sourcesJson));
            } catch (DuplicateKeyException exception) {
                if (exception.getMessage() == null
                        || !exception.getMessage().contains("uk_supplier_score_change_key")) {
                    throw exception;
                }
            }
        }
    }

    /**
     * 构造单条日志实体。
     *
     * 来源和人工身份必须完整，基础指标未变时只为本条日志标明衍生分校正，不影响其他条目。
     */
    private SupplierScoreChangeLog buildLog(ScoreChangeBatchCommand command, ScoreChangeLogEntry entry,
                                           String changeKey, String sourcesJson) {
        // 1. 填充批次级字段：幂等键、批次号、规则版本、供应商ID
        SupplierScoreChangeLog log = new SupplierScoreChangeLog();
        log.setChangeKey(changeKey);
        log.setBatchNo(command.getBatchNo());
        log.setRuleVersion(command.getRuleVersion());
        log.setSupplierId(command.getSupplierId());
        // 2. 填充条目级字段：供货产品ID、指标类型、前后分值
        log.setSupplierProductId(entry.getSupplierProductId());
        log.setMetricType(entry.getMetricType().name());
        log.setMetricScoreBefore(entry.getMetricScoreBefore());
        log.setMetricScoreAfter(entry.getMetricScoreAfter());
        log.setProductRecommendScoreBefore(entry.getProductRecommendScoreBefore());
        log.setProductRecommendScoreAfter(entry.getProductRecommendScoreAfter());
        log.setSupplierOverallScoreBefore(entry.getSupplierOverallScoreBefore());
        log.setSupplierOverallScoreAfter(entry.getSupplierOverallScoreAfter());
        // 3. 填充触发与来源字段
        log.setTriggerType(command.getTriggerType() == null ? null : command.getTriggerType().name());
        log.setRelatedSources(sourcesJson);
        // 4. 填充操作人与原因
        log.setOperatorType(command.getOperatorType());
        log.setOperatorId(command.getOperatorId());
        log.setOperatorName(command.getOperatorName() == null ? "" : command.getOperatorName());
        log.setReason(command.getReason() == null ? "" : command.getReason());
        // 5. 基础指标未变但衍生分（综合分/推荐分）变化时，追加校正说明到原因
        if (Objects.equals(entry.getMetricScoreBefore(), entry.getMetricScoreAfter())) {
            String correction = resolveCorrection(entry);
            if (correction != null) {
                String reason = log.getReason().isEmpty() ? correction : log.getReason() + "；" + correction;
                if (reason.length() > 600) {
                    throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "评分日志原因和校正说明合计不能超过600个字符");
                }
                log.setReason(reason);
            }
        }
        return log;
    }

    /**
     * 判断基础指标未变时衍生分是否发生变化，返回校正说明；无校正返回 null。
     */
    private String resolveCorrection(ScoreChangeLogEntry entry) {
        if (entry.getSupplierProductId() == null
                && !Objects.equals(entry.getSupplierOverallScoreBefore(), entry.getSupplierOverallScoreAfter())) {
            return "仅供应商综合分校正";
        }
        if (entry.getSupplierProductId() != null
                && !Objects.equals(entry.getProductRecommendScoreBefore(), entry.getProductRecommendScoreAfter())) {
            return "仅供货产品推荐分校正";
        }
        return null;
    }

    /** 校验公共必填文本，避免数据库默认值掩盖调用方漏传字段。 */
    private void requireText(String value, int maxLength, String name) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), name + "不能为空且不能超过" + maxLength + "个字符");
        }
    }

    /** 完整来源按类型和ID去重并稳定排序，同一对象编号冲突直接拒绝，不能静默覆盖。 */
    private String serializeSources(List<ScoreChangeSource> sources) {
        // 1. 按类型+ID去重并稳定排序（TreeMap），同一key冲突的业务编号不一致则拒绝
        var normalized = new TreeMap<String, ScoreChangeSource>();
        if (sources != null) {
            for (ScoreChangeSource source : sources) {
                // 1.1 校验单条来源：类型非空、ID为合法正整数、业务编号非空且≤64字符
                if (source == null || source.getBusinessType() == null || source.getBusinessId() == null
                        || !BUSINESS_ID_PATTERN.matcher(source.getBusinessId()).matches()
                        || new BigInteger(source.getBusinessId()).compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
                        || (source.getBusinessNo() != null && (source.getBusinessNo().isBlank() || source.getBusinessNo().length() > 64))) {
                    throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "评分日志来源类型、正整数ID或业务编号不合法");
                }
                // 1.2 同一类型+ID的来源，业务编号冲突则拒绝
                String key = source.getBusinessType().name() + ":" + source.getBusinessId();
                ScoreChangeSource previous = normalized.putIfAbsent(key, source);
                if (previous != null && !Objects.equals(previous.getBusinessNo(), source.getBusinessNo())) {
                    throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "同一评分来源存在冲突的业务编号");
                }
            }
        }
        // 2. 序列化为JSON字符串
        try {
            return objectMapper.writeValueAsString(List.copyOf(normalized.values()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("评分日志来源序列化失败", exception);
        }
    }

    /**
     * 实体列表转 VO 列表。分数字段统一从 INT×100 转业务小数,空值保持 null。
     */
    private List<SupplierScoreChangeLogVo> toVoList(List<SupplierScoreChangeLog> entities) {
        return entities.stream().map(this::toVo).toList();
    }

    /**
     * 把 dto 中的筛选条件挂到 query wrapper 上;空值不挂,保持可选查询语义。
     */
    private void applyFilters(LambdaQueryWrapper<SupplierScoreChangeLog> wrapper,
                              SupplierScoreChangeLogPageDto dto) {
        if (dto == null) {
            return;
        }
        // 精确匹配：指标类型、触发类型、批次号
        wrapper.eq(StrUtil.isNotBlank(dto.getMetricType()),
                SupplierScoreChangeLog::getMetricType, dto.getMetricType());
        wrapper.eq(StrUtil.isNotBlank(dto.getTriggerType()),
                SupplierScoreChangeLog::getTriggerType, dto.getTriggerType());
        wrapper.eq(StrUtil.isNotBlank(dto.getBatchNo()),
                SupplierScoreChangeLog::getBatchNo, dto.getBatchNo());
        // 时间区间：创建时间 ≥ startTime 且 ≤ endTime
        wrapper.ge(dto.getStartTime() != null,
                SupplierScoreChangeLog::getCreateTime, dto.getStartTime());
        wrapper.le(dto.getEndTime() != null,
                SupplierScoreChangeLog::getCreateTime, dto.getEndTime());
    }

    /**
     * 实体转 VO。分数字段统一从 INT×100 转业务小数,空值保持 null。
     */
    private SupplierScoreChangeLogVo toVo(SupplierScoreChangeLog entity) {
        SupplierScoreChangeLogVo vo = new SupplierScoreChangeLogVo();
        // 1. 基础ID字段
        vo.setScoreChangeLogId(entity.getId());
        vo.setSupplierId(entity.getSupplierId());
        vo.setSupplierProductId(entity.getSupplierProductId());
        // 2. 指标类型与分值（INT×100 → 业务小数）
        vo.setMetricType(entity.getMetricType());
        vo.setMetricScoreBefore(QtyUtil.toDecimal(entity.getMetricScoreBefore()));
        vo.setMetricScoreAfter(QtyUtil.toDecimal(entity.getMetricScoreAfter()));
        vo.setProductRecommendScoreBefore(QtyUtil.toDecimal(entity.getProductRecommendScoreBefore()));
        vo.setProductRecommendScoreAfter(QtyUtil.toDecimal(entity.getProductRecommendScoreAfter()));
        vo.setSupplierOverallScoreBefore(QtyUtil.toDecimal(entity.getSupplierOverallScoreBefore()));
        vo.setSupplierOverallScoreAfter(QtyUtil.toDecimal(entity.getSupplierOverallScoreAfter()));
        // 3. 触发与批次信息
        vo.setTriggerType(entity.getTriggerType());
        vo.setBatchNo(entity.getBatchNo());
        vo.setRuleVersion(entity.getRuleVersion());
        // 4. 反序列化来源列表JSON
        try {
            List<ScoreChangeSource> sources = objectMapper.readValue(entity.getRelatedSources(), new TypeReference<>() {});
            if (sources == null) throw new IllegalStateException("评分日志来源不能为JSON null");
            vo.setRelatedSources(sources);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("评分日志来源反序列化失败", exception);
        }
        // 5. 操作人与原因
        vo.setOperatorType(entity.getOperatorType());
        vo.setOperatorId(entity.getOperatorId());
        vo.setOperatorName(entity.getOperatorName());
        vo.setReason(entity.getReason());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }
}