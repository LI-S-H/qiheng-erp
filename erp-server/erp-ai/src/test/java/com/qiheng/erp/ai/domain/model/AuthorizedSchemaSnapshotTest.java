package com.qiheng.erp.ai.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 授权 Schema 快照：结构化清单的读写与指纹。
 *
 * @author Li
 * @since 2026-10-25
 */
class AuthorizedSchemaSnapshotTest {

    private AuthorizedSchemaSnapshot snapshot() {
        return AuthorizedSchemaSnapshot.of("【DB_ID】 erp",
                List.of(new AuthorizedSchemaSnapshot.Table("product", "产品表", "product", true, "id"),
                        new AuthorizedSchemaSnapshot.Table("purchase_order", "采购订单", "purchase", false, "id")),
                Map.of("product", List.of(
                                new AuthorizedSchemaSnapshot.Column("id", false, true),
                                new AuthorizedSchemaSnapshot.Column("name", false, false),
                                new AuthorizedSchemaSnapshot.Column("cost_price", true, false)),
                        "purchase_order", List.of(
                                new AuthorizedSchemaSnapshot.Column("id", false, true),
                                new AuthorizedSchemaSnapshot.Column("order_no", false, false))));
    }

    @Test
    void tableAndColumnLookupsWork() {
        AuthorizedSchemaSnapshot snapshot = snapshot();

        assertThat(snapshot.tableNames()).containsExactly("product", "purchase_order");
        assertThat(snapshot.hasTable("product")).isTrue();
        assertThat(snapshot.hasTable("employee_salary")).isFalse();
        assertThat(snapshot.hasColumn("product", "name")).isTrue();
        assertThat(snapshot.hasColumn("product", "remark")).isFalse();
        assertThat(snapshot.allColumnNames()).contains("id", "name", "order_no", "cost_price");
        assertThat(snapshot.columnNames("product")).containsExactly("id", "name", "cost_price");
    }

    @Test
    void columnFactsAreCarried() {
        AuthorizedSchemaSnapshot snapshot = snapshot();

        assertThat(snapshot.findColumn("product", "id").primaryKey()).isTrue();
        assertThat(snapshot.findColumn("product", "cost_price").nullable()).isTrue();
        assertThat(snapshot.findColumn("product", "name").nullable()).isFalse();
        assertThat(snapshot.primaryKeyOf("product")).isEqualTo("id");
        assertThat(snapshot.primaryKeyOf("nope")).isNull();
    }

    @Test
    void logicalDeleteFlagIsCarriedPerTable() {
        AuthorizedSchemaSnapshot snapshot = snapshot();

        assertThat(snapshot.hasLogicalDelete("product")).isTrue();
        assertThat(snapshot.hasLogicalDelete("purchase_order")).isFalse();
    }

    @Test
    void mapRoundTripKeepsEverything() {
        AuthorizedSchemaSnapshot original = snapshot();

        Map<String, Object> map = original.toMap();
        AuthorizedSchemaSnapshot restored = AuthorizedSchemaSnapshot.fromMap(map);

        assertThat(restored.schemaText()).isEqualTo(original.schemaText());
        assertThat(restored.tableNames()).isEqualTo(original.tableNames());
        assertThat(restored.columns()).isEqualTo(original.columns());
        assertThat(restored.fingerprint()).isEqualTo(original.fingerprint());
        assertThat(restored.hasLogicalDelete("product")).isTrue();
        assertThat(restored.primaryKeyOf("purchase_order")).isEqualTo("id");
        assertThat(restored.findColumn("product", "cost_price").nullable()).isTrue();
    }

    @Test
    void mapKeepsLegacyKeysForExistingReaders() {
        Map<String, Object> map = snapshot().toMap();

        // planner / need_review 读的是 schema 文本与表数
        assertThat(map.get("schema")).isEqualTo("【DB_ID】 erp");
        assertThat(map.get("tableCount")).isEqualTo(2);
    }

    @Test
    void oldColumnShapeIsStillReadable() {
        // 旧检查点里 columns 是纯列名数组：按"可见、非主键"还原，不炸
        Map<String, Object> map = Map.of(
                "schema", "t",
                "tables", List.of(Map.of("name", "product")),
                "columns", Map.of("product", List.of("id", "name")));

        AuthorizedSchemaSnapshot restored = AuthorizedSchemaSnapshot.fromMap(map);

        assertThat(restored.columnNames("product")).containsExactly("id", "name");
        assertThat(restored.findColumn("product", "id").primaryKey()).isFalse();
    }

    @Test
    void fingerprintChangesWithVisibleScope() {
        String first = snapshot().fingerprint();
        String second = AuthorizedSchemaSnapshot.of("【DB_ID】 erp",
                List.of(new AuthorizedSchemaSnapshot.Table("product", "产品表", "product", true, "id")),
                Map.of("product", List.of(
                        new AuthorizedSchemaSnapshot.Column("id", false, true),
                        new AuthorizedSchemaSnapshot.Column("name", false, false)))).fingerprint();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void fingerprintIgnoresColumnOrder() {
        String first = AuthorizedSchemaSnapshot.of("t",
                List.of(new AuthorizedSchemaSnapshot.Table("product", "产品表", "product", false, "id")),
                Map.of("product", List.of(
                        new AuthorizedSchemaSnapshot.Column("id", false, true),
                        new AuthorizedSchemaSnapshot.Column("name", false, false)))).fingerprint();
        String second = AuthorizedSchemaSnapshot.of("t",
                List.of(new AuthorizedSchemaSnapshot.Table("product", "产品表", "product", false, "id")),
                Map.of("product", List.of(
                        new AuthorizedSchemaSnapshot.Column("name", false, false),
                        new AuthorizedSchemaSnapshot.Column("id", false, true)))).fingerprint();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void emptySnapshotIsSafe() {
        AuthorizedSchemaSnapshot empty = AuthorizedSchemaSnapshot.empty();

        assertThat(empty.schemaText()).isEmpty();
        assertThat(empty.tableNames()).isEmpty();
        assertThat(empty.hasTable("product")).isFalse();
        assertThat(AuthorizedSchemaSnapshot.fromMap(Map.of()).tableNames()).isEmpty();
        assertThat(AuthorizedSchemaSnapshot.fromMap(null).tables()).isEmpty();
    }
}