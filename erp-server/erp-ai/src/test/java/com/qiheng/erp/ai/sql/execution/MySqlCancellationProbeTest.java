package com.qiheng.erp.ai.sql.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 隔离库只执行 SLEEP，观察 MySQL 驱动的中断与取消行为。 */
@EnabledIfEnvironmentVariable(named = "ERP_MYSQL_CANCEL_PROBE", matches = "true")
class MySqlCancellationProbeTest {
    /** 验证按任务 ID 登记的真实 MySQL 语句可以由取消入口找到。 */
    @Test
    void registeredStatementCanBeCancelledByRunId() throws Exception {
        var cancellation = new SqlStatementCancellation();
        var active = cancellation.begin("probe-run");
        var started = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var outcome = new AtomicReference<String>("unfinished");
        try (var connection = DriverManager.getConnection(System.getenv("DATASET_IT_URL"),
                System.getenv("DATASET_IT_USER"), System.getenv("DATASET_IT_PASSWORD"));
             var statement = connection.createStatement();
             var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                try {
                    active.register(statement);
                    started.countDown();
                    try (var result = statement.executeQuery("SELECT SLEEP(3)")) {
                        result.next();
                        outcome.set("result:" + result.getInt(1));
                    }
                } catch (SQLException error) {
                    outcome.set("sql-error:" + error.getClass().getSimpleName());
                } finally {
                    active.clear(statement);
                    cancellation.finish("probe-run", active);
                    finished.countDown();
                }
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(500);
            cancellation.cancel("probe-run");
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(outcome.get()).isNotEqualTo("result:0").isNotEqualTo("unfinished");
        }
    }

    @Test
    void observesThreadInterruptAndStatementCancel() throws Exception {
        probe(false);
        probe(true);
    }

    private void probe(boolean cancelStatement) throws Exception {
        String url = System.getenv("DATASET_IT_URL");
        String user = System.getenv("DATASET_IT_USER");
        String password = System.getenv("DATASET_IT_PASSWORD");
        var entered = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var outcome = new AtomicReference<String>("unfinished");
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement();
             var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> {
                entered.countDown();
                try (var result = statement.executeQuery("SELECT SLEEP(3)")) {
                    result.next();
                    outcome.set("success:" + result.getInt(1));
                } catch (SQLException error) {
                    outcome.set("sql-error:" + error.getClass().getSimpleName());
                } finally {
                    finished.countDown();
                }
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(500);
            long started = System.nanoTime();
            if (cancelStatement) {
                statement.cancel();
            } else {
                future.cancel(true);
            }
            boolean stoppedQuickly = finished.await(1200, TimeUnit.MILLISECONDS);
            long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
            System.out.println("MYSQL_CANCEL_PROBE statementCancel=" + cancelStatement
                    + " stoppedQuickly=" + stoppedQuickly + " elapsedMillis=" + elapsed
                    + " outcome=" + outcome.get());
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
