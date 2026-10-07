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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
                .thenThrow(new DuplicateKeyException("uk_system_exception_dedup 冲突"));

        // 重复消息：唯一键冲突直接 ACK 跳过，不外抛（外抛会让 MQ 无意义重投）
        assertThatCode(() -> consumer.onMessage(validMessage())).doesNotThrowAnyException();
        verify(systemExceptionMapper).insert(any(SystemException.class));
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
