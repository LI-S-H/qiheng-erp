package com.qiheng.erp.purchase.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.util.HashUtil;
import com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreChangeBatchCommand;
import com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreChangeLogEntry;
import com.qiheng.erp.purchase.domain.supplierscore.dto.SupplierScoreChangeLogPageDto;
import com.qiheng.erp.purchase.domain.supplierscore.entity.SupplierScoreChangeLog;
import com.qiheng.erp.purchase.domain.supplierscore.enums.MetricType;
import com.qiheng.erp.purchase.domain.supplierscore.enums.TriggerType;
import com.qiheng.erp.purchase.domain.supplierscore.vo.SupplierScoreChangeLogVo;
import com.qiheng.erp.purchase.mapper.SupplierScoreChangeLogMapper;
import com.qiheng.erp.purchase.service.ISupplierScoreChangeLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SupplierScoreChangeLogServiceImpl 单元测试。
 *
 * <p>验证分页查询、appendBatch 幂等、changeKey 稳定性。</p>
 */
@ExtendWith(MockitoExtension.class)
class SupplierScoreChangeLogServiceImplTest {

    @Mock
    private SupplierScoreChangeLogMapper supplierScoreChangeLogMapper;

    private SupplierScoreChangeLogServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SupplierScoreChangeLog.class);
        service = new SupplierScoreChangeLogServiceImpl();
        ReflectionTestUtils.setField(service, "supplierScoreChangeLogMapper", supplierScoreChangeLogMapper);
        ReflectionTestUtils.setField(service, "objectMapper", new com.fasterxml.jackson.databind.ObjectMapper());
    }

    // ===== pageScoreChangeLogs =====

    @Test
    void pageScoreChangeLogsShouldRejectInvalidSupplierProductId() {
        SupplierScoreChangeLogPageDto dto = new SupplierScoreChangeLogPageDto();
        dto.setSupplierProductId("invalid");

        BizException exception = assertThrows(BizException.class, () -> service.pageScoreChangeLogs(dto));

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), exception.getCode());
        verify(supplierScoreChangeLogMapper, never()).selectPage(any(), any());
    }

    @Test
    void pageScoreChangeLogsShouldReturnEmptyWhenNoLogs() {
        Page<SupplierScoreChangeLog> emptyPage = new Page<>(1, 10);
        emptyPage.setRecords(Collections.emptyList());
        emptyPage.setTotal(0L);
        when(supplierScoreChangeLogMapper.selectPage(any(), any())).thenReturn(emptyPage);

        PageResult<SupplierScoreChangeLogVo> result = service.pageScoreChangeLogs(new SupplierScoreChangeLogPageDto());

        assertEquals(0, result.getRecords().size());
        assertEquals(0, result.getTotal());
    }

    @Test
    void pageScoreChangeLogsShouldConvertEntityToVo() {
        SupplierScoreChangeLog entity = sampleLog();
        Page<SupplierScoreChangeLog> page = new Page<>(1, 10);
        page.setRecords(List.of(entity));
        page.setTotal(1L);
        when(supplierScoreChangeLogMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<SupplierScoreChangeLogVo> result = service.pageScoreChangeLogs(new SupplierScoreChangeLogPageDto());

        assertEquals(1, result.getRecords().size());
        SupplierScoreChangeLogVo vo = result.getRecords().get(0);
        assertEquals(entity.getId(), vo.getScoreChangeLogId());
        assertEquals(entity.getSupplierId(), vo.getSupplierId());
        assertEquals(entity.getSupplierProductId(), vo.getSupplierProductId());
        assertEquals("PRICE", vo.getMetricType());
        assertEquals(entity.getReason(), vo.getReason());
        assertEquals(entity.getBatchNo(), vo.getBatchNo());
        assertEquals(entity.getRuleVersion(), vo.getRuleVersion());
    }

    // ===== 可选 ID 条件 =====

    @Test
    void pageScoreChangeLogsShouldRejectInvalidSupplierId() {
        SupplierScoreChangeLogPageDto dto = new SupplierScoreChangeLogPageDto();
        dto.setSupplierId("invalid");

        BizException exception = assertThrows(BizException.class, () -> service.pageScoreChangeLogs(dto));

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), exception.getCode());
    }

    @Test
    void pageScoreChangeLogsShouldReturnEmptyWhenFilteredIdHasNoLogs() {
        Page<SupplierScoreChangeLog> emptyPage = new Page<>(1, 10);
        emptyPage.setRecords(Collections.emptyList());
        emptyPage.setTotal(0L);
        when(supplierScoreChangeLogMapper.selectPage(any(), any())).thenReturn(emptyPage);

        SupplierScoreChangeLogPageDto dto = new SupplierScoreChangeLogPageDto();
        dto.setSupplierId("100");
        PageResult<SupplierScoreChangeLogVo> result = service.pageScoreChangeLogs(dto);

        assertEquals(0, result.getRecords().size());
    }

    // ===== appendBatch =====

    @Test
    void appendBatchShouldInsertAllEntries() {
        ScoreChangeBatchCommand cmd = sampleCommand();
        service.appendBatch(cmd);

        ArgumentCaptor<SupplierScoreChangeLog> captor = ArgumentCaptor.forClass(SupplierScoreChangeLog.class);
        verify(supplierScoreChangeLogMapper, times(2)).insert(captor.capture());
        List<SupplierScoreChangeLog> logs = captor.getAllValues();
        assertEquals(2, logs.size());
        assertEquals("SC2026092300001", logs.get(0).getBatchNo());
        assertEquals("SCORE_V1", logs.get(0).getRuleVersion());
        assertEquals("SYSTEM", logs.get(0).getOperatorType());
        assertEquals("自动入库", logs.get(0).getOperatorName());
        assertNull(logs.get(0).getOperatorId());
        assertEquals(64, logs.get(0).getChangeKey().length());
        assertEquals(64, logs.get(1).getChangeKey().length());
    }

    @Test
    void appendBatchShouldBeNoOpWhenCommandIsNull() {
        service.appendBatch(null);
        verify(supplierScoreChangeLogMapper, never()).insert(any(SupplierScoreChangeLog.class));
    }

    @Test
    void appendBatchShouldBeNoOpWhenEntriesIsEmpty() {
        ScoreChangeBatchCommand cmd = sampleCommand();
        cmd.setEntries(Collections.emptyList());
        service.appendBatch(cmd);
        verify(supplierScoreChangeLogMapper, never()).insert(any(SupplierScoreChangeLog.class));
    }

    @Test
    void appendBatchShouldGenerateStableChangeKey() {
        // 同一入参调两次,changeKey 应一致
        service.appendBatch(sampleCommand());
        service.appendBatch(sampleCommand());

        ArgumentCaptor<SupplierScoreChangeLog> captor = ArgumentCaptor.forClass(SupplierScoreChangeLog.class);
        verify(supplierScoreChangeLogMapper, times(4)).insert(captor.capture());
        List<SupplierScoreChangeLog> logs = captor.getAllValues();
        // 两次调用的 PRICE 条目 changeKey 相同
        assertEquals(logs.get(0).getChangeKey(), logs.get(2).getChangeKey());
        // 两次调用的 QUALITY 条目 changeKey 相同
        assertEquals(logs.get(1).getChangeKey(), logs.get(3).getChangeKey());
    }

    @Test
    void appendBatchChangeKeyIsSha256OfConcat() {
        // 单 entry 测试,断言仅 PRICE 一条
        ScoreChangeBatchCommand cmd = sampleCommand();
        cmd.setEntries(Collections.singletonList(cmd.getEntries().get(0)));

        service.appendBatch(cmd);

        ArgumentCaptor<SupplierScoreChangeLog> captor = ArgumentCaptor.forClass(SupplierScoreChangeLog.class);
        verify(supplierScoreChangeLogMapper, times(1)).insert(captor.capture());
        String actualKey = captor.getValue().getChangeKey();
        String expectedKey = HashUtil.sha256HexConcat(
                "SC2026092300001", "100", "PRICE", "123");
        assertEquals(expectedKey, actualKey);
    }

    @Test
    void appendBatchShouldMapNullSupplierProductIdAsNullString() {
        ScoreChangeBatchCommand cmd = new ScoreChangeBatchCommand();
        cmd.setBatchNo("SC2026092300002");
        cmd.setRuleVersion("SCORE_V1");
        cmd.setSupplierId(200L);
        cmd.setTriggerType(TriggerType.SERVICE_TRIGGER);
        cmd.setReason("服务分调整");
        cmd.setOperatorType("USER");
        cmd.setOperatorId(1L);
        cmd.setOperatorName("管理员");
        ScoreChangeLogEntry entry = new ScoreChangeLogEntry();
        entry.setMetricType(MetricType.SERVICE);
        entry.setSupplierProductId(null);
        entry.setMetricScoreBefore(8000);
        entry.setMetricScoreAfter(8500);
        cmd.setEntries(Collections.singletonList(entry));

        service.appendBatch(cmd);

        ArgumentCaptor<SupplierScoreChangeLog> captor = ArgumentCaptor.forClass(SupplierScoreChangeLog.class);
        verify(supplierScoreChangeLogMapper).insert(captor.capture());
        assertNull(captor.getValue().getSupplierProductId());
        // 期望 key 中 supplierProductId 字段用 NULL 字符串拼接
        String expectedKey = HashUtil.sha256HexConcat(
                "SC2026092300002", "200", "SERVICE", null);
        assertEquals(expectedKey, captor.getValue().getChangeKey());
    }

    @Test
    void appendBatchShouldThrowWhenSupplierIdIsNull() {
        ScoreChangeBatchCommand cmd = sampleCommand();
        cmd.setSupplierId(null);

        BizException exception = assertThrows(BizException.class, () -> service.appendBatch(cmd));
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), exception.getCode());
        verify(supplierScoreChangeLogMapper, never()).insert(any(SupplierScoreChangeLog.class));
    }

    @Test
    void appendBatchShouldThrowWhenMetricTypeIsNull() {
        ScoreChangeBatchCommand cmd = sampleCommand();
        cmd.getEntries().get(0).setMetricType(null);

        BizException exception = assertThrows(BizException.class, () -> service.appendBatch(cmd));
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), exception.getCode());
        verify(supplierScoreChangeLogMapper, never()).insert(any(SupplierScoreChangeLog.class));
    }

    @Test
    void appendBatchShouldSkipEntriesWithNoScoreChange() {
        ScoreChangeBatchCommand cmd = sampleCommand();
        // 指标分、推荐分、综合分全部未变才跳过，不能漏掉仅衍生分发生变化的记录。
        cmd.getEntries().get(1).setMetricScoreBefore(8000);
        cmd.getEntries().get(1).setMetricScoreAfter(8000);
        cmd.getEntries().get(1).setProductRecommendScoreAfter(cmd.getEntries().get(1).getProductRecommendScoreBefore());
        cmd.getEntries().get(1).setSupplierOverallScoreAfter(cmd.getEntries().get(1).getSupplierOverallScoreBefore());

        service.appendBatch(cmd);

        ArgumentCaptor<SupplierScoreChangeLog> captor = ArgumentCaptor.forClass(SupplierScoreChangeLog.class);
        verify(supplierScoreChangeLogMapper, times(1)).insert(captor.capture());
        assertEquals(MetricType.PRICE.name(), captor.getValue().getMetricType());
    }

    @Test
    void appendBatchShouldIgnoreOnlyChangeKeyDuplicates() {
        ScoreChangeBatchCommand cmd = sampleCommand();
        cmd.setEntries(List.of(cmd.getEntries().get(0)));
        when(supplierScoreChangeLogMapper.insert(any(SupplierScoreChangeLog.class))).thenThrow(
                new DuplicateKeyException("Duplicate entry for key 'uk_supplier_score_change_key'"));

        service.appendBatch(cmd);
        verify(supplierScoreChangeLogMapper).insert(any(SupplierScoreChangeLog.class));
    }

    @Test
    void appendBatchShouldExposeOtherDuplicateKeys() {
        ScoreChangeBatchCommand cmd = sampleCommand();
        cmd.setEntries(List.of(cmd.getEntries().get(0)));
        when(supplierScoreChangeLogMapper.insert(any(SupplierScoreChangeLog.class))).thenThrow(
                new DuplicateKeyException("Duplicate entry for key 'PRIMARY'"));

        assertThrows(DuplicateKeyException.class, () -> service.appendBatch(cmd));
    }

    // ===== pageScoreChangeLogs 筛选 =====

    @Test
    void pageScoreChangeLogsShouldApplyFiltersFromDto() {
        Page<SupplierScoreChangeLog> emptyPage = new Page<>(1, 10);
        emptyPage.setRecords(Collections.emptyList());
        emptyPage.setTotal(0L);
        LambdaQueryWrapper<SupplierScoreChangeLog>[] capturedWrapper = new LambdaQueryWrapper[1];
        when(supplierScoreChangeLogMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            capturedWrapper[0] = invocation.getArgument(1);
            return emptyPage;
        });

        SupplierScoreChangeLogPageDto dto = new SupplierScoreChangeLogPageDto();
        dto.setSupplierId("100");
        dto.setSupplierProductId("123");
        dto.setMetricType("PRICE");
        dto.setTriggerType("PRICE_TRIGGER");
        dto.setBatchNo("SC2026092300001");
        dto.setStartTime(java.time.LocalDateTime.of(2026, 9, 1, 0, 0, 0));
        dto.setEndTime(java.time.LocalDateTime.of(2026, 9, 23, 23, 59, 59));
        service.pageScoreChangeLogs(dto);

        // 两个 ID 与其余五个条件同时提供时，应全部按 AND 参与查询。
        int baselineSize = invokeAndMeasureNormalSize(new SupplierScoreChangeLogPageDto());
        int withFiltersSize = readNormalSegmentCount(capturedWrapper[0]);
        assertTrue(withFiltersSize - baselineSize >= 7,
                "查询条件未全部生效:baseline=" + baselineSize + ",withFilters=" + withFiltersSize);
        String sql = capturedWrapper[0].getSqlSegment();
        assertTrue(sql.contains("supplier_id ="));
        assertTrue(sql.contains("supplier_product_id ="));
        assertTrue(sql.contains("metric_type ="));
        assertTrue(sql.contains("batch_no ="));
        assertTrue(sql.contains("ORDER BY"));
        assertEquals(7, capturedWrapper[0].getParamNameValuePairs().size());
    }

    @Test
    void pageScoreChangeLogsShouldRejectReversedTimeRange() {
        SupplierScoreChangeLogPageDto dto = new SupplierScoreChangeLogPageDto();
        dto.setStartTime(java.time.LocalDateTime.of(2026, 9, 24, 0, 0));
        dto.setEndTime(java.time.LocalDateTime.of(2026, 9, 23, 0, 0));

        BizException exception = assertThrows(BizException.class, () -> service.pageScoreChangeLogs(dto));
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), exception.getCode());
        verify(supplierScoreChangeLogMapper, never()).selectPage(any(), any());
    }

    /** 调一次 pageScoreChangeLogs(空 dto)返回 normal.size,作为后续筛选对比基准。 */
    private int invokeAndMeasureNormalSize(SupplierScoreChangeLogPageDto dto) {
        LambdaQueryWrapper<SupplierScoreChangeLog>[] captured = new LambdaQueryWrapper[1];
        org.mockito.Mockito.doAnswer(invocation -> {
            captured[0] = invocation.getArgument(1);
            return new Page<SupplierScoreChangeLog>(1, 10);
        }).when(supplierScoreChangeLogMapper).selectPage(any(), any());
        try {
            service.pageScoreChangeLogs(dto);
        } finally {
            org.mockito.Mockito.reset(supplierScoreChangeLogMapper);
        }
        return readNormalSegmentCount(captured[0]);
    }

    /**
     * 取 LambdaQueryWrapper 内部 expression.normal 列表的 size,作为"普通 where 条件"数量的间接验证。
     * 每个 .like/.eq/.ge/.le 调用都会向 normal 追加一个 ISqlSegment;orderBy 走 orderBy 列表,不影响 normal。
     */
    private static int readNormalSegmentCount(LambdaQueryWrapper<?> wrapper) {
        try {
            Object expression = readFieldRecursive(wrapper, "expression");
            if (expression == null) {
                return -1;
            }
            Object normal = readFieldRecursive(expression, "normal");
            if (normal instanceof java.util.Collection<?> collection) {
                return collection.size();
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 沿继承链向上找指定字段名的 declared field,反射读出值。 */
    private static Object readFieldRecursive(Object target, String fieldName) {
        for (Class<?> clazz = target.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    // ===== writeServiceScoreLog 已在 Commit 1 移除,改为构造 ScoreChangeBatchCommand 直接调 appendBatch =====

    private ScoreChangeBatchCommand sampleCommand() {
        ScoreChangeLogEntry priceEntry = new ScoreChangeLogEntry();
        priceEntry.setMetricType(MetricType.PRICE);
        priceEntry.setSupplierProductId(123L);
        priceEntry.setMetricScoreBefore(8500);
        priceEntry.setMetricScoreAfter(9000);
        priceEntry.setProductRecommendScoreBefore(8800);
        priceEntry.setProductRecommendScoreAfter(9100);
        priceEntry.setSupplierOverallScoreBefore(8900);
        priceEntry.setSupplierOverallScoreAfter(9200);

        ScoreChangeLogEntry qualityEntry = new ScoreChangeLogEntry();
        qualityEntry.setMetricType(MetricType.QUALITY);
        qualityEntry.setSupplierProductId(123L);
        qualityEntry.setMetricScoreBefore(7500);
        qualityEntry.setMetricScoreAfter(8000);
        qualityEntry.setProductRecommendScoreBefore(8800);
        qualityEntry.setProductRecommendScoreAfter(9100);
        qualityEntry.setSupplierOverallScoreBefore(8900);
        qualityEntry.setSupplierOverallScoreAfter(9200);

        ScoreChangeBatchCommand cmd = new ScoreChangeBatchCommand();
        cmd.setBatchNo("SC2026092300001");
        cmd.setRuleVersion("SCORE_V1");
        cmd.setSupplierId(100L);
        cmd.setTriggerType(TriggerType.INBOUND_TRIGGER);
        cmd.setRelatedSources(List.of(source("98765", "PO-202609-0015")));
        cmd.setOperatorType("SYSTEM");
        cmd.setOperatorId(null);
        cmd.setOperatorName("自动入库");
        cmd.setReason("入库单 RK-202609-0015 完全入库");
        List<ScoreChangeLogEntry> entries = new ArrayList<>();
        entries.add(priceEntry);
        entries.add(qualityEntry);
        cmd.setEntries(entries);
        return cmd;
    }

    private SupplierScoreChangeLog sampleLog() {
        SupplierScoreChangeLog entity = new SupplierScoreChangeLog();
        entity.setId(1L);
        entity.setSupplierId(100L);
        entity.setSupplierProductId(123L);
        entity.setMetricType("PRICE");
        entity.setMetricScoreBefore(8500);
        entity.setMetricScoreAfter(9000);
        entity.setProductRecommendScoreBefore(8800);
        entity.setProductRecommendScoreAfter(9100);
        entity.setSupplierOverallScoreBefore(8900);
        entity.setSupplierOverallScoreAfter(9200);
        entity.setTriggerType("PRICE_TRIGGER");
        entity.setBatchNo("SC2026092300001");
        entity.setRuleVersion("SCORE_V1");
        entity.setRelatedSources("[]");
        entity.setOperatorType("USER");
        entity.setOperatorId(1L);
        entity.setOperatorName("admin");
        entity.setReason("报价调整");
        entity.setCreateTime(java.time.LocalDateTime.of(2026, 9, 21, 14, 0, 0));
        return entity;
    }

    /** 构造采购单来源，ID 与编号保持同一业务对象。 */
    private com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreChangeSource source(String id, String no) {
        return com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreChangeSource.builder()
                .businessType(com.qiheng.erp.purchase.domain.supplierscore.enums.ScoreSourceBusinessType.PURCHASE_ORDER)
                .businessId(id).businessNo(no).build();
    }

    @Test
    void allTwelveSourcesArePersistedOnceAndReturnedAsTypedArray() throws Exception {
        var cmd = sampleCommand();
        var sources = new ArrayList<com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreChangeSource>();
        for (int i = 12; i >= 1; i--) sources.add(source(String.valueOf(1000 + i), "PO-" + i));
        sources.add(source("1001", "PO-1"));
        cmd.setRelatedSources(sources);
        service.appendBatch(cmd);
        var captor = ArgumentCaptor.forClass(SupplierScoreChangeLog.class);
        verify(supplierScoreChangeLogMapper, times(2)).insert(captor.capture());
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var tree = json.readTree(captor.getAllValues().getFirst().getRelatedSources());
        assertTrue(tree.isArray(), "必须是 JSON 数组而不是双重编码字符串");
        assertEquals(12, tree.size(), "超过十张采购单也必须完整记录");
        assertEquals("1001", tree.get(0).get("businessId").asText());
        assertEquals("PO-1", tree.get(0).get("businessNo").asText());
        assertEquals(captor.getAllValues().getFirst().getRelatedSources(), captor.getAllValues().getLast().getRelatedSources());
        var page = new Page<SupplierScoreChangeLog>(1, 20);
        page.setRecords(captor.getAllValues()); page.setTotal(2);
        when(supplierScoreChangeLogMapper.selectPage(any(), any())).thenReturn(page);
        var vo = service.pageScoreChangeLogs(new SupplierScoreChangeLogPageDto()).getRecords().getFirst();
        assertEquals(12, vo.getRelatedSources().size());
        assertTrue(json.valueToTree(vo).get("relatedSources").isArray());
    }

    @Test
    void conflictingSourceNumbersRejectWholeBatch() {
        var cmd = sampleCommand();
        cmd.setRelatedSources(List.of(source("1001", "PO-1"), source("1001", "PO-OTHER")));
        assertThrows(BizException.class, () -> service.appendBatch(cmd));
        verify(supplierScoreChangeLogMapper, never()).insert(any(SupplierScoreChangeLog.class));
    }

    @Test
    void invalidMetadataAndSourceAreRejectedBeforeWriting() {
        List<java.util.function.Consumer<ScoreChangeBatchCommand>> invalid = List.of(
                c -> c.setBatchNo(" "), c -> c.setRuleVersion(null), c -> c.setTriggerType(null),
                c -> c.setOperatorType("UNKNOWN"), c -> c.setOperatorId(9L),
                c -> c.setOperatorName(" "), c -> c.setOperatorName("名".repeat(101)),
                c -> c.setReason("原".repeat(501)),
                c -> c.setRelatedSources(List.of(source("0", "PO-0"))),
                c -> c.setRelatedSources(List.of(source("9223372036854775808", "PO-LARGE"))),
                c -> c.setRelatedSources(List.of(source("9007199254740993", "号".repeat(65)))),
                c -> c.getEntries().getFirst().setMetricScoreAfter(10001),
                c -> c.getEntries().getFirst().setMetricScoreBefore(-1));
        for (var change : invalid) {
            var cmd = sampleCommand(); change.accept(cmd);
            assertThrows(BizException.class, () -> service.appendBatch(cmd));
        }
        verify(supplierScoreChangeLogMapper, never()).insert(any(SupplierScoreChangeLog.class));
    }

    @Test
    void userIdentityIsRequiredAndNullableScoresRemainNull() {
        var invalid = sampleCommand(); invalid.setOperatorType("USER");
        assertThrows(BizException.class, () -> service.appendBatch(invalid));
        var cmd = sampleCommand(); cmd.setOperatorType("USER"); cmd.setOperatorId(700L); cmd.setOperatorName("评分审阅员");
        cmd.getEntries().getFirst().setMetricScoreAfter(null);
        service.appendBatch(cmd);
        var captor = ArgumentCaptor.forClass(SupplierScoreChangeLog.class);
        verify(supplierScoreChangeLogMapper, times(2)).insert(captor.capture());
        assertEquals(700L, captor.getValue().getOperatorId());
        assertNull(captor.getAllValues().getFirst().getMetricScoreAfter());
    }

    @Test
    void derivedOnlyReasonSuffixDoesNotPolluteOtherMetricLogs() {
        var cmd = sampleCommand();
        cmd.getEntries().getLast().setMetricScoreBefore(8000);
        cmd.getEntries().getLast().setMetricScoreAfter(8000);
        service.appendBatch(cmd);
        var captor = ArgumentCaptor.forClass(SupplierScoreChangeLog.class);
        verify(supplierScoreChangeLogMapper, times(2)).insert(captor.capture());
        assertEquals(cmd.getReason(), captor.getAllValues().getFirst().getReason());
        assertTrue(captor.getAllValues().getLast().getReason().contains("仅供货产品推荐分校正"));
    }

    @Test
    void maximumLegalUserReasonKeepsAllFiveHundredCharactersWithDerivedCorrection() {
        var cmd = sampleCommand(); cmd.setOperatorType("USER"); cmd.setOperatorId(700L);
        cmd.setOperatorName("评分审阅员"); cmd.setReason("原".repeat(500));
        cmd.getEntries().getLast().setMetricScoreBefore(8000);
        cmd.getEntries().getLast().setMetricScoreAfter(8000);
        service.appendBatch(cmd);
        var captor = ArgumentCaptor.forClass(SupplierScoreChangeLog.class);
        verify(supplierScoreChangeLogMapper, times(2)).insert(captor.capture());
        assertEquals(cmd.getReason(), captor.getAllValues().getFirst().getReason());
        String derivedReason = captor.getAllValues().getLast().getReason();
        assertTrue(derivedReason.startsWith(cmd.getReason()), "合法人工原因不能截断");
        assertTrue(derivedReason.contains("仅供货产品推荐分校正"));
        assertTrue(derivedReason.length() > 500 && derivedReason.length() <= 600);
    }

    @Test
    void productionNonNullJacksonStillReturnsExplicitNullsForSourceSystemActorAndScores() {
        var entity = sampleLog();
        entity.setRelatedSources("[{\"businessType\":\"SUPPLIER_PRODUCT\",\"businessId\":\"9007199254740993\",\"businessNo\":null}]");
        entity.setOperatorType("SYSTEM"); entity.setOperatorId(null); entity.setOperatorName("报价到期校正");
        entity.setMetricScoreAfter(null); entity.setSupplierOverallScoreAfter(null);
        var page = new Page<SupplierScoreChangeLog>(1, 10); page.setRecords(List.of(entity)); page.setTotal(1);
        when(supplierScoreChangeLogMapper.selectPage(any(), any())).thenReturn(page);
        var vo = service.pageScoreChangeLogs(new SupplierScoreChangeLogPageDto()).getRecords().getFirst();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        var tree = json.valueToTree(vo);
        var source = tree.get("relatedSources").get(0);
        assertTrue(source.has("businessNo"), "全局 NON_NULL 不得让来源编号缺失");
        assertTrue(source.get("businessNo").isNull());
        assertEquals("9007199254740993", source.get("businessId").asText());
        assertTrue(tree.has("operatorId") && tree.get("operatorId").isNull());
        assertTrue(tree.has("metricScoreAfter") && tree.get("metricScoreAfter").isNull());
        assertTrue(tree.has("supplierOverallScoreAfter") && tree.get("supplierOverallScoreAfter").isNull());
    }
}
