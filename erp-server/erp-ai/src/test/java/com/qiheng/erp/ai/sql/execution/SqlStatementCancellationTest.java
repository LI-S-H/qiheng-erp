package com.qiheng.erp.ai.sql.execution;

import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SqlStatementCancellationTest {
    @Test
    void slowCancelDoesNotBlockStatementCleanup() throws Exception {
        var query = new SqlStatementCancellation.ActiveQuery();
        var statement = mock(Statement.class);
        var cancelStarted = new CountDownLatch(1);
        var releaseCancel = new CountDownLatch(1);
        doAnswer(invocation -> {
            cancelStarted.countDown();
            releaseCancel.await(2, TimeUnit.SECONDS);
            return null;
        }).when(statement).cancel();
        query.register(statement);
        Thread canceller = Thread.ofPlatform().start(() -> {
            try {
                query.cancel();
            } catch (SQLException error) {
                throw new IllegalStateException(error);
            }
        });
        try {
            assertThat(cancelStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Thread cleaner = Thread.ofPlatform().start(() -> query.clear(statement));
            cleaner.join(500);
            assertThat(cleaner.isAlive()).isFalse();
        } finally {
            releaseCancel.countDown();
            canceller.join(2000);
        }
    }

    @Test
    void cancelsOnlyMatchingRunAndPreventsFurtherPages() throws Exception {
        var registry = new SqlStatementCancellation();
        var first = registry.begin("first");
        var second = registry.begin("second");
        var firstStatement = mock(Statement.class);
        var secondStatement = mock(Statement.class);
        first.register(firstStatement);
        second.register(secondStatement);
        registry.cancel("first");
        verify(firstStatement).cancel();
        verify(secondStatement, never()).cancel();
        first.clear(firstStatement);
        assertThatThrownBy(() -> first.register(mock(Statement.class))).isInstanceOf(SQLException.class);
        registry.finish("first", first);
        registry.finish("second", second);
    }
}
