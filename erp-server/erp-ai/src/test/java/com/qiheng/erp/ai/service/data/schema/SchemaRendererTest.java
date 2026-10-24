package com.qiheng.erp.ai.service.data.schema;

import com.qiheng.erp.ai.domain.entity.AiDataTable;
import com.qiheng.erp.ai.domain.entity.AiDataTableColumn;
import com.qiheng.erp.ai.domain.entity.AiDataTableRelation;
import com.qiheng.erp.ai.domain.model.IndexDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema 渲染契约单测：四条渲染规则各自的可观察行为。
 *
 * <p>渲染文本是模型唯一的数据依据，格式一变模型的理解就变，
 * 所以这里把规则钉死成断言，而不是靠人眼看。</p>
 *
 * @author Li
 * @since 2026-10-21
 */
class SchemaRendererTest {

    private final SchemaRenderer renderer = new SchemaRenderer();

    @Test
    void tableHeaderCarriesBusinessNameAndDescription() {
        String schema = renderer.render(
                List.of(table("product", "产品表", "产品主档。数量字段按 100 倍存储")),
                Map.of("product", List.of(column("id", "BIGINT", "产品ID", "产品ID", null, 1, 0))),
                List.of());

        assertThat(schema).startsWith("【DB_ID】 erp\n");
        assertThat(schema).contains("# Table: product, 产品表");
        assertThat(schema).contains("# 产品主档。数量字段按 100 倍存储");
    }

    @Test
    void descriptionIsKeptOnTheSameLineAsColumn() {
        String schema = renderer.render(
                List.of(table("product", "产品表", "")),
                Map.of("product", List.of(column("safety_stock_qty", "BIGINT", "安全库存数量",
                        "安全库存数量，按100倍整数存储，例如12.50存1250", null, 0, 0))),
                List.of());

        // 口径必须与列同行，模型才不会漏掉「除以 100」
        assertThat(schema).contains(
                "(safety_stock_qty:BIGINT, 安全库存数量，按100倍整数存储，例如12.50存1250)");
    }

    @Test
    void primaryKeyAndUniqueKeyAreMarked() {
        String schema = renderer.render(
                List.of(table("product", "产品表", "")),
                Map.of("product", List.of(
                        column("id", "BIGINT", "产品ID", "产品ID", null, 1, 0),
                        column("product_code", "VARCHAR(64)", "产品编码", "产品编码", null, 0, 1))),
                List.of());

        assertThat(schema).contains("(id:BIGINT, 产品ID, Primary Key)");
        assertThat(schema).contains("(product_code:VARCHAR(64), 产品编码, Unique)");
    }

    @Test
    void letterEnumGetsExamplesAndDuplicateValuesAreStrippedFromDescription() {
        String schema = renderer.render(
                List.of(table("inbound_bill", "入库单主表", "")),
                Map.of("inbound_bill", List.of(column("status", "VARCHAR(32)", "状态",
                        "状态：DRAFT、PENDING_CONFIRM、CONFIRMED、CANCELLED",
                        List.of("DRAFT", "PENDING_CONFIRM", "CONFIRMED", "CANCELLED"), 0, 0))),
                List.of());

        // 枚举值只出现在 Examples 一处，描述里不重复
        assertThat(schema).contains(
                "(status:VARCHAR(32), 状态, Examples: [\"DRAFT\", \"PENDING_CONFIRM\", \"CONFIRMED\", \"CANCELLED\"])");
        assertThat(schema).doesNotContain("状态：DRAFT");
    }

    @Test
    void numericEnumKeepsDescriptionAndGetsNoExamples() {
        String schema = renderer.render(
                List.of(table("product", "产品表", "")),
                Map.of("product", List.of(column("status", "TINYINT", "状态",
                        "状态：1启用，0禁用", List.of("1", "0"), 0, 0))),
                List.of());

        // 数字枚举的含义描述里已经说清楚，再给 Examples 是纯冗余
        assertThat(schema).contains("(status:TINYINT, 状态：1启用，0禁用)");
        assertThat(schema).doesNotContain("Examples");
    }

    @Test
    void indexesAreRenderedInCompactForm() {
        AiDataTable table = table("inbound_bill", "入库单主表", "");
        table.setIndexDefs(List.of(
                new IndexDefinition("idx_inbound_bill_type_status", false, List.of("inbound_type", "status")),
                new IndexDefinition("uk_inbound_bill_no", true, List.of("inbound_no"))));

        String schema = renderer.render(List.of(table), Map.of(), List.of());

        assertThat(schema).contains("# Indexes: idx_inbound_bill_type_status(inbound_type,status) uk_inbound_bill_no(inbound_no)");
    }

    @Test
    void relationsWhoseBothEndsAreVisibleAreRendered() {
        String schema = renderer.render(
                List.of(table("purchase_order", "采购订单主表", ""), table("purchase_order_item", "采购订单明细表", "")),
                Map.of(),
                List.of(relation("purchase_order_item", "purchase_order_id", "purchase_order", "id", "N:1", "所属采购订单")));

        assertThat(schema).contains("【Foreign keys】");
        assertThat(schema).contains("purchase_order_item.purchase_order_id=purchase_order.id [N:1] 所属采购订单");
    }

    @Test
    void relationWithInvisibleEndIsNotRendered() {
        // 采购员看不到 purchase_order 时，这条关系也不给，否则模型会去 JOIN 不存在的表
        String schema = renderer.render(
                List.of(table("purchase_order_item", "采购订单明细表", "")),
                Map.of(),
                List.of(relation("purchase_order_item", "purchase_order_id", "purchase_order", "id", "N:1", "所属采购订单")));

        assertThat(schema).doesNotContain("【Foreign keys】");
        assertThat(schema).doesNotContain("purchase_order_id");
    }

    @Test
    void emptyVisibleScopeProducesEmptySchema() {
        assertThat(renderer.render(List.of(), Map.of(), List.of())).isEmpty();
    }

    private AiDataTable table(String name, String businessName, String description) {
        AiDataTable table = new AiDataTable();
        table.setTableName(name);
        table.setBusinessName(businessName);
        table.setDescription(description);
        table.setModuleCode("product");
        table.setStatus(1);
        return table;
    }

    private AiDataTableColumn column(String name, String type, String businessName, String description,
                                      List<String> enums, int primaryKey, int uniqueKey) {
        AiDataTableColumn column = new AiDataTableColumn();
        column.setColumnName(name);
        column.setColumnType(type);
        column.setBusinessName(businessName);
        column.setDescription(description);
        column.setEnumValues(enums);
        column.setPrimaryKey(primaryKey);
        column.setUniqueKey(uniqueKey);
        column.setStatus(1);
        return column;
    }

    private AiDataTableRelation relation(String fromTable, String fromColumn, String toTable, String toColumn,
                                         String cardinality, String description) {
        AiDataTableRelation relation = new AiDataTableRelation();
        relation.setFromTable(fromTable);
        relation.setFromColumn(fromColumn);
        relation.setToTable(toTable);
        relation.setToColumn(toColumn);
        relation.setCardinality(cardinality);
        relation.setDescription(description);
        relation.setStatus(1);
        return relation;
    }
}
