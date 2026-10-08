package com.qiheng.erp.admin;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** 无需外部环境，验证业务扫描范围不变且隔离测试配置不会被完整应用误扫描。 */
class ApplicationTestConfigurationIsolationTest {

    @Test
    void applicationScanMustKeepBootTestExclusionFilters() {
        assertNull(ErpApplication.class.getDeclaredAnnotation(ComponentScan.class),
                "额外 ComponentScan 会绕过 Boot 的测试排除规则");
        assertArrayEquals(new String[]{"com.qiheng.erp"},
                ErpApplication.class.getAnnotation(SpringBootApplication.class).scanBasePackages());
        ComponentScan scan = AnnotatedElementUtils.findMergedAnnotation(ErpApplication.class, ComponentScan.class);
        assertNotNull(scan);
        var excluded = Arrays.stream(scan.excludeFilters()).flatMap(filter -> Arrays.stream(filter.classes())).toList();
        assertTrue(excluded.contains(TypeExcludeFilter.class));
        assertTrue(excluded.contains(AutoConfigurationExcludeFilter.class));
    }

    @Test
    void realExceptionConfigurationMustRemainExplicitTestConfiguration() {
        assertNotNull(SystemExceptionRecordClosedLoopIT.RealExceptionConfiguration.class
                .getDeclaredAnnotation(TestConfiguration.class), "真实 MQ 夹具只能由对应测试显式装配");
    }
}
