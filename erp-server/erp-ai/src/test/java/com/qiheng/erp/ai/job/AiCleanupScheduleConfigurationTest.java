package com.qiheng.erp.ai.job;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AiCleanupScheduleConfigurationTest {
    @Test
    void longOrphanScanDoesNotBlockDefaultScheduledTasks() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(TestConfiguration.class)) {
            var probe = context.getBean(Probe.class);
            try {
                assertThat(probe.orphanStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(probe.otherStarted.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                probe.release.countDown();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import(AiCleanupScheduleConfiguration.class)
    static class TestConfiguration {
        @Bean
        Probe probe() {
            return new Probe();
        }
    }

    static class Probe {
        final CountDownLatch orphanStarted = new CountDownLatch(1);
        final CountDownLatch otherStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Scheduled(fixedDelay = 3600000, scheduler = "aiOrphanCleanupScheduler")
        public void orphan() throws InterruptedException {
            orphanStarted.countDown();
            release.await(5, TimeUnit.SECONDS);
        }

        @Scheduled(fixedDelay = 3600000)
        public void other() {
            otherStarted.countDown();
        }
    }
}
