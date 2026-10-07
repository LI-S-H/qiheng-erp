package com.qiheng.erp.purchase.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.domain.purchaseorder.enums.PurchaseOrderStatus;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierBatchDeleteDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierBatchStatusDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierCreateDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierUpdateDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierServiceScoreDto;
import com.qiheng.erp.purchase.domain.supplier.entity.Supplier;
import com.qiheng.erp.purchase.domain.supplier.vo.SupplierBatchFailure;
import com.qiheng.erp.purchase.domain.supplier.vo.SupplierVo;
import com.qiheng.erp.purchase.domain.supplierproduct.entity.SupplierProduct;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.purchase.mapper.SupplierMapper;
import com.qiheng.erp.purchase.mapper.SupplierProductMapper;
import com.qiheng.erp.purchase.service.SupplierScoreRecalculateService;
import com.qiheng.erp.returnorder.domain.entity.ReturnOrder;
import com.qiheng.erp.returnorder.domain.enums.ReturnStatus;
import com.qiheng.erp.returnorder.domain.port.ReturnType;
import com.qiheng.erp.returnorder.mapper.ReturnOrderMapper;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SupplierServiceImpl 单元测试。
 * <p>重点验证本次重构的几处改动:
 * <ul>
 *     <li>create/update 对 supplierName 做 trim,可选字段统一空串</li>
 *     <li>ensureCanDisable 文案改为"无法停用"</li>
 *     <li>batchUpdateStatus 加事务且停用分支前置批量校验</li>
 *     <li>batchDelete 返回 List&lt;SupplierBatchFailure&gt; 而非 Map</li>
 *     <li>updateServiceScore 清空分数时 reason 写空字符串</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupplierServiceImplTest {

    @Mock
    private SupplierMapper supplierMapper;
    @Mock
    private SupplierProductMapper supplierProductMapper;
    @Mock
    private PurchaseOrderMapper purchaseOrderMapper;
    @Mock
    private ReturnOrderMapper returnOrderMapper;
    @Mock
    private com.qiheng.erp.common.util.CodeNoGenerator codeNoGenerator;
    @Mock
    private SupplierScoreRecalculateService supplierScoreRecalculateService;

    private SupplierServiceImpl service;

    private MockedStatic<UserContext> userContextMock;

    @BeforeEach
    void setUp() {
        // 触发 mp 元数据初始化,让 LambdaUpdateWrapper 可用
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""), Supplier.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""), SupplierProduct.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""), PurchaseOrder.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""), ReturnOrder.class);

        service = new SupplierServiceImpl();
        ReflectionTestUtils.setField(service, "supplierMapper", supplierMapper);
        ReflectionTestUtils.setField(service, "supplierProductMapper", supplierProductMapper);
        ReflectionTestUtils.setField(service, "purchaseOrderMapper", purchaseOrderMapper);
        ReflectionTestUtils.setField(service, "returnOrderMapper", returnOrderMapper);
        ReflectionTestUtils.setField(service, "codeNoGenerator", codeNoGenerator);
        ReflectionTestUtils.setField(service, "supplierScoreRecalculateService", supplierScoreRecalculateService);
        when(supplierMapper.lockByIdForUpdate(any())).thenAnswer(invocation -> invocation.getArgument(0));

        LoginUser user = new LoginUser();
        user.setUserId(1L);
        user.setRealName("admin");
        userContextMock = mockStatic(UserContext.class);
        userContextMock.when(UserContext::requireCurrentUser).thenReturn(user);
    }

    @AfterEach
    void tearDown() {
        userContextMock.close();
    }

    // ===== create: trim supplierName =====

    @Test
    void createShouldTrimSupplierName() {
        SupplierCreateDto dto = createDto();
        dto.setSupplierName("  北京供应商  ");
        when(codeNoGenerator.nextNo(any(), any())).thenReturn("S0001");
        when(supplierMapper.insert(any(Supplier.class))).thenAnswer(inv -> {
            Supplier arg = inv.getArgument(0);
            arg.setId(1L);
            return 1;
        });
        when(supplierMapper.selectById(1L)).thenAnswer(inv -> {
            Supplier inserted = new Supplier();
            inserted.setId(1L);
            inserted.setSupplierName("北京供应商");
            inserted.setSupplierCode("S0001");
            return inserted;
        });

        SupplierVo vo = service.create(dto);

        assertEquals("北京供应商", vo.getSupplierName());
    }

    @Test
    void createShouldNormalizeOptionalFieldsToEmptyString() {
        SupplierCreateDto dto = createDto();
        dto.setSupplierName("供应商A");
        dto.setContactName(null);
        dto.setAddress(null);
        when(codeNoGenerator.nextNo(any(), any())).thenReturn("S0002");
        when(supplierMapper.insert(any(Supplier.class))).thenAnswer(inv -> {
            Supplier arg = inv.getArgument(0);
            arg.setId(2L);
            assertEquals("", arg.getContactName());
            assertEquals("", arg.getAddress());
            assertEquals("", arg.getContactPhone());
            assertEquals("", arg.getPaymentTerms());
            return 1;
        });
        when(supplierMapper.selectById(2L)).thenAnswer(inv -> {
            Supplier s = new Supplier();
            s.setId(2L);
            s.setSupplierName("供应商A");
            return s;
        });

        service.create(dto);
    }

    @Test
    void createShouldRejectInitialServiceScoreWithoutReason() {
        SupplierCreateDto dto = createDto();
        dto.setServiceScore(new BigDecimal("80"));
        dto.setServiceScoreReason(null);
        when(codeNoGenerator.nextNo(any(), any())).thenReturn("S0003");

        BizException exception = assertThrows(BizException.class, () -> service.create(dto));
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), exception.getCode());
        assertEquals("初始服务分与服务分原因必须同时填写或同时为空", exception.getMessage());
        verify(supplierMapper, never()).insert(any(Supplier.class));
    }

    // ===== update: trim supplierName =====

    @Test
    void updateShouldTrimSupplierName() {
        SupplierUpdateDto dto = new SupplierUpdateDto();
        dto.setSupplierName("  上海供应商  ");
        dto.setStatus(1);
        dto.setVersion(0);
        dto.setRemark("");
        Supplier existing = new Supplier();
        existing.setId(1L);
        existing.setSupplierName("原名");
        existing.setStatus(1);
        existing.setVersion(0);
        when(supplierMapper.selectById(1L)).thenReturn(existing);
        when(supplierMapper.updateById(any(Supplier.class))).thenAnswer(inv -> {
            Supplier arg = inv.getArgument(0);
            assertEquals("上海供应商", arg.getSupplierName());
            return 1;
        });
        when(supplierMapper.selectById(1L)).thenReturn(existing);

        service.update(1L, dto);
    }

    @Test
    void updateShouldThrowOnVersionMismatch() {
        SupplierUpdateDto dto = new SupplierUpdateDto();
        dto.setSupplierName("供应商");
        dto.setStatus(1);
        dto.setVersion(99);
        dto.setRemark("");
        Supplier existing = new Supplier();
        existing.setId(1L);
        existing.setStatus(1);
        existing.setVersion(0);
        when(supplierMapper.selectById(1L)).thenReturn(existing);
        when(supplierMapper.updateById(any(Supplier.class))).thenReturn(0);

        BizException exception = assertThrows(BizException.class, () -> service.update(1L, dto));
        assertEquals("供应商不存在或数据已发生变化，请刷新后重试", exception.getMessage());
    }

    // ===== ensureCanDisable: 文案 =====

    @Test
    void ensureCanDisableShouldThrowWithDisabledMessage() {
        // update: 从启用切到停用,有供货产品 → 应报"无法停用"
        SupplierUpdateDto dto = new SupplierUpdateDto();
        dto.setSupplierName("供应商A");
        dto.setStatus(0);
        dto.setVersion(0);
        dto.setRemark("");
        Supplier existing = new Supplier();
        existing.setId(1L);
        existing.setStatus(1);
        existing.setVersion(0);
        when(supplierMapper.selectById(1L)).thenReturn(existing);
        when(supplierProductMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        BizException exception = assertThrows(BizException.class, () -> service.update(1L, dto));
        assertEquals("供应商存在供货产品，无法停用", exception.getMessage());
    }

    // ===== batchUpdateStatus: 加事务 + 批量前置校验 =====

    @Test
    void batchUpdateStatusShouldReusePreCollectedBlockReasons() {
        // 整批 2 条,仅 ID=1 存在供货产品 → 期望在 ID=1 时抛"无法停用"
        SupplierBatchStatusDto dto = new SupplierBatchStatusDto();
        dto.setSupplierIds(List.of("1", "2"));
        Map<String, Integer> versions = new HashMap<>();
        versions.put("1", 0);
        versions.put("2", 0);
        dto.setVersionBySupplierId(versions);
        dto.setStatus(0);
        Supplier s1 = new Supplier();
        s1.setId(1L);
        s1.setStatus(1);
        s1.setVersion(0);
        Supplier s2 = new Supplier();
        s2.setId(2L);
        s2.setStatus(1);
        s2.setVersion(0);
        when(supplierMapper.selectByIds(anyList())).thenReturn(List.of(s1, s2));
        // 批量收集返回 ID=1 是阻挡项(供货产品命中)
        when(supplierProductMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(makeSupplierProductWithId(1L)));
        when(purchaseOrderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(returnOrderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        BizException exception = assertThrows(BizException.class, () -> service.batchUpdateStatus(dto));
        assertEquals("供应商存在供货产品，无法停用", exception.getMessage());
        // 没有触发 N 次单条 ensureCanDisable 的额外 SQL(selectCount)
        verify(supplierProductMapper, never()).selectCount(any(LambdaQueryWrapper.class));
    }

    @Test
    void batchUpdateStatusShouldAllowWhenAllClear() {
        SupplierBatchStatusDto dto = new SupplierBatchStatusDto();
        dto.setSupplierIds(List.of("1", "2"));
        Map<String, Integer> versions = new HashMap<>();
        versions.put("1", 0);
        versions.put("2", 0);
        dto.setVersionBySupplierId(versions);
        dto.setStatus(0);
        Supplier s1 = new Supplier();
        s1.setId(1L);
        s1.setStatus(1);
        s1.setVersion(0);
        Supplier s2 = new Supplier();
        s2.setId(2L);
        s2.setStatus(1);
        s2.setVersion(0);
        when(supplierMapper.selectByIds(anyList())).thenReturn(List.of(s1, s2));
        when(supplierProductMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(purchaseOrderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(returnOrderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);

        service.batchUpdateStatus(dto);

        verify(supplierMapper, times(2)).updateById(any(Supplier.class));
    }

    @Test
    void batchUpdateStatusShouldAllowEnableWithoutDisableChecks() {
        // 启用操作不应触发停用分支校验
        SupplierBatchStatusDto dto = new SupplierBatchStatusDto();
        dto.setSupplierIds(List.of("1"));
        Map<String, Integer> versions = new HashMap<>();
        versions.put("1", 0);
        dto.setVersionBySupplierId(versions);
        dto.setStatus(1);
        Supplier s1 = new Supplier();
        s1.setId(1L);
        s1.setStatus(0);
        s1.setVersion(0);
        when(supplierMapper.selectByIds(anyList())).thenReturn(List.of(s1));
        when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);

        service.batchUpdateStatus(dto);

        verify(supplierProductMapper, never()).selectList(any(LambdaQueryWrapper.class));
        verify(purchaseOrderMapper, never()).selectList(any(LambdaQueryWrapper.class));
        verify(returnOrderMapper, never()).selectList(any(LambdaQueryWrapper.class));
    }

    // ===== batchDelete: 结构化返回 =====

    @Test
    void batchDeleteShouldReturnEmptyListWhenAllSucceed() {
        SupplierBatchDeleteDto dto = new SupplierBatchDeleteDto();
        dto.setSupplierIds(List.of("1", "2"));
        Map<String, Integer> versions = new HashMap<>();
        versions.put("1", 0);
        versions.put("2", 0);
        dto.setVersionBySupplierId(versions);
        // 无业务引用
        when(supplierProductMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(purchaseOrderMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(returnOrderMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(supplierMapper.deleteByIdWithVersion(eq(1L), eq(0))).thenReturn(1);
        when(supplierMapper.deleteByIdWithVersion(eq(2L), eq(0))).thenReturn(1);

        List<SupplierBatchFailure> failures = service.batchDelete(dto);

        assertEquals(0, failures.size());
    }

    @Test
    void batchDeleteShouldReturnFailureDetailWhenVersionMismatch() {
        SupplierBatchDeleteDto dto = new SupplierBatchDeleteDto();
        dto.setSupplierIds(List.of("1"));
        Map<String, Integer> versions = new HashMap<>();
        versions.put("1", 99); // 预期版本 99,DB 中是 0
        dto.setVersionBySupplierId(versions);
        when(supplierProductMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(purchaseOrderMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(returnOrderMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(supplierMapper.deleteByIdWithVersion(eq(1L), eq(99))).thenReturn(0);

        List<SupplierBatchFailure> failures = service.batchDelete(dto);

        assertEquals(1, failures.size());
        assertEquals("1", failures.get(0).getSupplierId());
        assertTrue(failures.get(0).getReason().contains("数据已发生变化"));
    }

    @Test
    void batchDeleteShouldRollbackWhenSupplierHasReferences() {
        SupplierBatchDeleteDto dto = new SupplierBatchDeleteDto();
        dto.setSupplierIds(List.of("1"));
        Map<String, Integer> versions = new HashMap<>();
        versions.put("1", 0);
        dto.setVersionBySupplierId(versions);
        when(supplierProductMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        BizException exception = assertThrows(BizException.class, () -> service.batchDelete(dto));
        assertEquals("供应商存在供货产品，无法删除", exception.getMessage());
        verify(supplierMapper, never()).deleteByIdWithVersion(any(), any(Integer.class));
    }

    // ===== updateServiceScore: 清空分数时 reason="" =====

    @Test
    void updateServiceScoreShouldClearReasonWhenServiceScoreIsNull() {
        SupplierServiceScoreDto dto = new SupplierServiceScoreDto();
        dto.setVersion(0);
        dto.setServiceScore(null);
        dto.setReason("清空原因");
        Supplier existing = new Supplier();
        existing.setId(1L);
        existing.setVersion(0);
        existing.setServiceScore(8000);
        existing.setServiceScoreReason("原原因");
        when(supplierMapper.selectById(1L)).thenReturn(existing);
        when(supplierMapper.update(eq(null), any())).thenReturn(1);
        when(supplierMapper.selectById(1L)).thenReturn(existing);

        service.updateServiceScore(1L, dto);

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Supplier>> captor =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(supplierMapper).update(eq(null), captor.capture());
        // service_score 置 null，service_score_reason 置空字符串以满足数据库约束。
        Map<String, Object> params = captor.getValue().getParamNameValuePairs();
        assertNotNull(params);
        assertTrue(params.containsKey("MPGENVAL1"));
        assertNull(params.get("MPGENVAL1"));
        assertTrue(params.containsKey("MPGENVAL2"));
        assertEquals("", params.get("MPGENVAL2"));
    }

    @Test
    void updateServiceScoreShouldKeepReasonWhenScoreNotNull() {
        SupplierServiceScoreDto dto = new SupplierServiceScoreDto();
        dto.setVersion(0);
        dto.setServiceScore(new BigDecimal("85.50"));
        dto.setReason("调整说明");
        Supplier existing = new Supplier();
        existing.setId(1L);
        existing.setVersion(0);
        existing.setServiceScore(null);
        existing.setServiceScoreReason(null);
        when(supplierMapper.selectById(1L)).thenReturn(existing);
        when(supplierMapper.update(eq(null), any())).thenReturn(1);
        when(supplierMapper.selectById(1L)).thenReturn(existing);

        service.updateServiceScore(1L, dto);

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Supplier>> captor =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(supplierMapper).update(eq(null), captor.capture());
        Map<String, Object> params = captor.getValue().getParamNameValuePairs();
        assertNotNull(params);
        assertEquals("调整说明", params.get("MPGENVAL2"));
    }

    @Test
    void updateServiceScoreShouldRecalculateWhenScoreChanged() {
        SupplierServiceScoreDto dto = new SupplierServiceScoreDto();
        dto.setVersion(0);
        dto.setServiceScore(new BigDecimal("85"));
        dto.setReason("调整");
        Supplier existing = new Supplier();
        existing.setId(1L);
        existing.setVersion(0);
        existing.setServiceScore(7500);
        when(supplierMapper.selectById(1L)).thenReturn(existing);
        when(supplierMapper.update(eq(null), any())).thenReturn(1);
        when(supplierMapper.selectById(1L)).thenReturn(existing);

        service.updateServiceScore(1L, dto);

        verify(supplierScoreRecalculateService).recalcForServiceScoreChange(1L, 7500, "调整");
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(supplierMapper, supplierScoreRecalculateService);
        order.verify(supplierMapper).lockByIdForUpdate(1L);
        order.verify(supplierMapper).selectById(1L);
        order.verify(supplierMapper).update(eq(null), any());
        order.verify(supplierScoreRecalculateService).recalcForServiceScoreChange(1L, 7500, "调整");
    }

    @Test
    void updateServiceScoreShouldPassPreviousScoreForConsecutiveChanges() {
        Supplier first = new Supplier();
        first.setId(1L);
        first.setVersion(0);
        first.setServiceScore(7500);
        Supplier second = new Supplier();
        second.setId(1L);
        second.setVersion(1);
        second.setServiceScore(8500);
        when(supplierMapper.selectById(1L)).thenReturn(first, first, second, second);
        when(supplierMapper.update(eq(null), any())).thenReturn(1);

        SupplierServiceScoreDto firstRequest = new SupplierServiceScoreDto();
        firstRequest.setVersion(0);
        firstRequest.setServiceScore(new BigDecimal("85"));
        firstRequest.setReason("第一次调整");
        SupplierServiceScoreDto secondRequest = new SupplierServiceScoreDto();
        secondRequest.setVersion(1);
        secondRequest.setServiceScore(new BigDecimal("90"));
        secondRequest.setReason("第二次调整");

        service.updateServiceScore(1L, firstRequest);
        service.updateServiceScore(1L, secondRequest);

        verify(supplierScoreRecalculateService).recalcForServiceScoreChange(1L, 7500, "第一次调整");
        verify(supplierScoreRecalculateService).recalcForServiceScoreChange(1L, 8500, "第二次调整");
    }

    @Test
    void updateServiceScoreShouldNotWriteChangeLogWhenScoreUnchanged() {
        SupplierServiceScoreDto dto = new SupplierServiceScoreDto();
        dto.setVersion(0);
        dto.setServiceScore(new BigDecimal("75"));
        dto.setReason("调整");
        Supplier existing = new Supplier();
        existing.setId(1L);
        existing.setVersion(0);
        existing.setServiceScore(7500);
        when(supplierMapper.selectById(1L)).thenReturn(existing);
        when(supplierMapper.update(eq(null), any())).thenReturn(1);
        when(supplierMapper.selectById(1L)).thenReturn(existing);

        service.updateServiceScore(1L, dto);

        verify(supplierScoreRecalculateService, never()).recalcForServiceScoreChange(any(), any(), any());
    }

    // ===== 工具 =====

    private SupplierCreateDto createDto() {
        SupplierCreateDto dto = new SupplierCreateDto();
        dto.setSupplierName("供应商A");
        dto.setStatus(1);
        dto.setRemark("");
        return dto;
    }

    private SupplierProduct makeSupplierProductWithId(Long supplierId) {
        SupplierProduct sp = new SupplierProduct();
        sp.setSupplierId(supplierId);
        return sp;
    }
}
