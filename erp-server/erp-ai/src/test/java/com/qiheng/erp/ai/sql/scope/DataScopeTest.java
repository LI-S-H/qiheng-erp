package com.qiheng.erp.ai.sql.scope;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据可见范围的推导：权限码 → 模块。
 *
 * <p>关键一条是动作口径：只有 query 与 manage 带来数据可见性。</p>
 *
 * @author Li
 * @since 2026-10-25
 */
class DataScopeTest {

    @Test
    void queryAndManageGrantDataVisibility() {
        DataScope scope = DataScope.of(List.of("purchase:query", "warehouse:manage"));

        assertThat(scope.modules()).containsExactly("purchase", "warehouse");
        assertThat(scope.isEmpty()).isFalse();
    }

    @Test
    void createOnlyGrantsNothing() {
        DataScope scope = DataScope.of(List.of("purchase:create"));

        assertThat(scope.modules()).isEmpty();
        assertThat(scope.isEmpty()).isTrue();
    }

    @Test
    void pageEntryPermissionGrantsNothing() {
        // ai:assistant:use 只是页面入口，模块前缀 ai 没有业务表
        DataScope scope = DataScope.of(List.of("ai:assistant:use"));

        assertThat(scope.modules()).isEmpty();
    }

    @Test
    void adminSeesEverything() {
        DataScope scope = DataScope.of(List.of("*"));

        assertThat(scope.admin()).isTrue();
        assertThat(scope.isEmpty()).isFalse();
    }

    @Test
    void codeWithoutActionGrantsNothing() {
        assertThat(DataScope.of(List.of("purchase")).modules()).isEmpty();
    }

    @Test
    void nestedActionIsNotADataAction() {
        // dashboard:overview:query 的动作段是 overview:query，不是 query
        assertThat(DataScope.of(List.of("dashboard:overview:query")).modules()).isEmpty();
    }

    @Test
    void nullAndBlankCodesAreTolerated() {
        DataScope scope = DataScope.of(java.util.Arrays.asList(null, "  ", "product:query"));

        assertThat(scope.modules()).containsExactly("product");
    }

    @Test
    void emptyPermissionCodesYieldEmptyScope() {
        assertThat(DataScope.of(List.of()).isEmpty()).isTrue();
        assertThat(DataScope.of(null).isEmpty()).isTrue();
    }
}
