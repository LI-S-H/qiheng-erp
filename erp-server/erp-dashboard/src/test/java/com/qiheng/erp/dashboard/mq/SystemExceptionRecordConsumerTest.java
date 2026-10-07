package com.qiheng.erp.dashboard.mq;

import com.qiheng.erp.common.constant.SystemExceptionConstants;
import com.qiheng.erp.common.mq.SystemExceptionRecordMessage;
import com.qiheng.erp.common.util.BillNoGenerator;
import com.qiheng.erp.dashboard.domain.exception.entity.SystemException;
import com.qiheng.erp.dashboard.mapper.SystemExceptionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证系统异常记录消费者：必填校验、唯一键强幂等、编号生成与字段归一化。
 *
 * @author Li
 * @since 2026-10-07
 */
@ExtendWith(MockitoExtension.class)
class SystemExceptionRecordConsumerTest {

    @Mock
    private SystemExceptionMapper systemExceptionMapper;
    @Mock
    private BillNoGenerator billNoGenerator;

    private SystemExceptionRecordConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new SystemExceptionRecordConsumer(systemExceptionMapper, billNoGenerator);
    }

    private SystemExceptionRecordMessage validMessage() {
        SystemExceptionRecordMessage message = new SystemExceptionRecordMessage();
        message.setEventId("123456789");
        message.setExceptionType(SystemExceptionConstants.TYPE_SYSTEM_ERROR);
        message.setSourceModule("SALES");
        message.setSourceNo("POST /sales/order");
        message.setSeverity(SystemExceptionConstants.SEVERITY_HIGH);
        message.setErrorCode("99999");
        message.setErrorMessage("空指针");
        message.setDetailSummary("销售模块接口 POST /sales/order 抛出 RuntimeException");
        message.setOccurredAt(1728000000123L);
        return message;
    }

    @Test
    void consumesValidMessageAndPersistsNormalizedEntity() {
        when(billNoGenerator.nextNo(eq("SE"), any())).thenReturn("SE2026100700001");

        consumer.onMessage(validMessage());

        ArgumentCaptor<SystemException> captor = ArgumentCaptor.forClass(SystemException.class);
        verify(systemExceptionMapper).insert(captor.capture());
        SystemException saved = captor.getValue();
        assertThat(saved.getExceptionNo()).isEqualTo("SE2026100700001");
        assertThat(saved.getEventId()).isEqualTo("123456789");
        assertThat(saved.getExceptionType()).isEqualTo("SYSTEM_ERROR");
        assertThat(saved.getSourceModule()).isEqualTo("SALES");
        assertThat(saved.getSeverity()).isEqualTo("HIGH");
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        // 毫秒时间戳按业务时区转 LocalDateTime 并截断到秒（表字段 DATETIME）
        assertThat(saved.getOccurredAt()).isEqualTo(LocalDateTime.of(2024, 10, 4, 8, 0, 0));
    }

    @Test
    void skipsMessageMissingRequiredFields() {
        SystemExceptionRecordMessage message = validMessage();
        message.setErrorMessage("  ");

        consumer.onMessage(message);

        verify(systemExceptionMapper, never()).insert(any(SystemException.class));
    }

    @Test
    void skipsNullMessage() {
        consumer.onMessage(null);
        verify(systemExceptionMapper, never()).insert(any(SystemException.class));
    }

    @Test
    void skipsDuplicatedMessageByUniqueKeyConflict() {
        when(billNoGenerator.nextNo(eq("SE"), any())).thenReturn("SE2026100700002");
        when(systemExceptionMapper.insert(any(SystemException.class)))
                .thenThrow(new DuplicateKeyException("事件唯一键冲突", new SQLException(
                        "Duplicate entry '123456789' for key 'system_exception.uk_system_exception_event_id'",
                        "23000", 1062)));

        // 重复消息：唯一键冲突直接 ACK 跳过，不外抛（外抛会让 MQ 无意义重投）
        assertThatCode(() -> consumer.onMessage(validMessage())).doesNotThrowAnyException();
        verify(systemExceptionMapper).insert(any(SystemException.class));
    }

    @Test
    void keepsDifferentEventsWithSameSourceErrorAndSecond() {
        when(billNoGenerator.nextNo(eq("SE"), any())).thenReturn("SE2026100700005", "SE2026100700006");
        SystemExceptionRecordMessage first = validMessage();
        SystemExceptionRecordMessage second = validMessage();
        second.setEventId("123456790");
        consumer.onMessage(first);
        consumer.onMessage(second);
        ArgumentCaptor<SystemException> captor = ArgumentCaptor.forClass(SystemException.class);
        verify(systemExceptionMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(SystemException::getEventId)
                .containsExactly("123456789", "123456790");
        assertThat(captor.getAllValues()).extracting(SystemException::getOccurredAt)
                .containsOnly(LocalDateTime.of(2024, 10, 4, 8, 0, 0));
    }

    @Test
    void skipsMissingOrInvalidEventId() {
        for (String invalid : new String[]{null, "", " ", "0", "-1", "01", "abc", "9223372036854775808"}) {
            SystemExceptionRecordMessage message = validMessage();
            message.setEventId(invalid);
            consumer.onMessage(message);
        }
        verify(systemExceptionMapper, never()).insert(any(SystemException.class));
        verify(billNoGenerator, never()).nextNo(any(), any());
    }

    @Test
    void rethrowsOtherOrUnidentifiedUniqueKeyConflicts() {
        when(billNoGenerator.nextNo(eq("SE"), any())).thenReturn("SE2026100700007");
        for (String sqlMessage : new String[]{
                "Duplicate entry 'SE1' for key 'uk_system_exception_no'",
                "Duplicate entry '1' for key 'uk_system_exception_event_id_extra'",
                "Duplicate entry mentioning uk_system_exception_event_id without exact key"}) {
            DuplicateKeyException failure = new DuplicateKeyException("数据库唯一键冲突",
                    new SQLException(sqlMessage, "23000", 1062));
            when(systemExceptionMapper.insert(any(SystemException.class))).thenThrow(failure);
            assertThatThrownBy(() -> consumer.onMessage(validMessage())).isSameAs(failure);
        }
    }

    @Test
    void rethrowsDuplicateExceptionWithoutMatchingSqlCause() {
        when(billNoGenerator.nextNo(eq("SE"), any())).thenReturn("SE2026100700008");
        DuplicateKeyException failure = new DuplicateKeyException("uk_system_exception_event_id");
        when(systemExceptionMapper.insert(any(SystemException.class))).thenThrow(failure);
        assertThatThrownBy(() -> consumer.onMessage(validMessage())).isSameAs(failure);
    }

    @Test
    void acceptsUnqualifiedEventIndexButRejectsWrongSqlErrorCodeOrState() {
        when(billNoGenerator.nextNo(eq("SE"), any())).thenReturn("SE2026100700009");
        String sqlMessage = "Duplicate entry '123456789' for key 'uk_system_exception_event_id'";
        when(systemExceptionMapper.insert(any(SystemException.class))).thenThrow(new DuplicateKeyException(
                "重复事件", new SQLException(sqlMessage, "23000", 1062)));
        assertThatCode(() -> consumer.onMessage(validMessage())).doesNotThrowAnyException();
        for (SQLException sqlFailure : new SQLException[]{new SQLException(sqlMessage, "23000", 1213),
                new SQLException(sqlMessage, "40001", 1062)}) {
            DuplicateKeyException failure = new DuplicateKeyException("非事件唯一冲突", sqlFailure);
            when(systemExceptionMapper.insert(any(SystemException.class))).thenThrow(failure);
            assertThatThrownBy(() -> consumer.onMessage(validMessage())).isSameAs(failure);
        }
    }

    @Test
    void normalizesUnknownSeverityToMedium() {
        when(billNoGenerator.nextNo(eq("SE"), any())).thenReturn("SE2026100700003");
        SystemExceptionRecordMessage message = validMessage();
        message.setSeverity("CRITICAL");

        consumer.onMessage(message);

        ArgumentCaptor<SystemException> captor = ArgumentCaptor.forClass(SystemException.class);
        verify(systemExceptionMapper).insert(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo("MEDIUM");
    }

    @Test
    void rethrowsInsertFailureForMqRetry() {
        when(billNoGenerator.nextNo(eq("SE"), any())).thenReturn("SE2026100700004");
        when(systemExceptionMapper.insert(any(SystemException.class))).thenThrow(new RuntimeException("DB 闪断"));

        // 非唯一键冲突的数据库异常必须向外抛出让 MQ 重投，不能吞掉丢消息
        assertThatThrownBy(() -> consumer.onMessage(validMessage()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB 闪断");
    }
}
