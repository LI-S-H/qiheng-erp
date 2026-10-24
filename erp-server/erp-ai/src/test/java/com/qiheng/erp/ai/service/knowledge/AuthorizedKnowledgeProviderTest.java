package com.qiheng.erp.ai.service.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 授权业务知识提供者的契约测试。
 *
 * <p>核心是三件事：模块推导要和授权 Schema 同源（权限码前缀）、
 * 公共口径人人注入、没权限的模块不注入。</p>
 *
 * <p>断言用各文档的一级标题做标记——标题是模块的稳定标识，
 * 不会因为正文里出现跨模块引用（比如采购文档提到"销售订单"）而误判。</p>
 */
class AuthorizedKnowledgeProviderTest {

    private static final String SHARED = "# 公共业务口径";
    private static final String PRODUCT = "# 产品业务口径";
    private static final String CUSTOMER = "# 客户业务口径";
    private static final String SUPPLIER = "# 供应商业务口径";
    private static final String PURCHASE = "# 采购业务口径";
    private static final String SALES = "# 销售业务口径";
    private static final String RETURN = "# 退货业务口径";
    private static final String WAREHOUSE = "# 仓储业务口径";

    private final AuthorizedKnowledgeProvider provider = new AuthorizedKnowledgeProvider();

    @Test
    void purchaseStaffGetsPurchaseWarehouseProductSupplierAndReturnKnowledge() {
        // 采购员角色的真实权限码组合
        String knowledge = provider.provide(List.of(
                "product:query", "warehouse:query", "supplier:query", "supplier:create",
                "purchase:query", "purchase:create", "purchase:manage", "return:query",
                "dashboard:overview:query", "ai:assistant:use"));

        assertThat(knowledge).contains(PURCHASE, WAREHOUSE, PRODUCT, SUPPLIER, RETURN, SHARED);
        // 客户与销售不是采购员的可见范围
        assertThat(knowledge).doesNotContain(CUSTOMER, SALES);
    }

    @Test
    void sharedKnowledgeIsInjectedForEverybody() {
        String knowledge = provider.provide(List.of("product:query"));

        assertThat(knowledge).contains(SHARED);
        assertThat(knowledge).contains("放大了 100 倍");
    }

    @Test
    void adminGetsAllKnowledge() {
        String knowledge = provider.provide(List.of("*"));

        assertThat(knowledge).contains(SHARED, PRODUCT, CUSTOMER, SUPPLIER, PURCHASE, SALES, RETURN, WAREHOUSE);
    }

    @Test
    void moduleWithoutDocumentIsSimplySkipped() {
        // ai:assistant:use 的模块前缀是 ai，没有对应口径文档
        String knowledge = provider.provide(List.of("ai:assistant:use"));

        // 只有公共口径，没有业务模块口径
        assertThat(knowledge).contains(SHARED);
        assertThat(knowledge).doesNotContain(PURCHASE, WAREHOUSE, PRODUCT);
    }

    @Test
    void emptyPermissionCodesYieldSharedOnly() {
        String knowledge = provider.provide(List.of());

        assertThat(knowledge).contains(SHARED);
        assertThat(knowledge).doesNotContain(PURCHASE, WAREHOUSE);
    }

    @Test
    void nullPermissionCodesAreTolerated() {
        assertThat(provider.provide(null)).contains(SHARED);
    }

    @Test
    void knowledgeOrderFollowsMasterDataThenTransactionThenWarehouse() {
        String knowledge = provider.provide(List.of("*"));

        int shared = knowledge.indexOf(SHARED);
        int product = knowledge.indexOf(PRODUCT);
        int purchase = knowledge.indexOf(PURCHASE);
        int warehouse = knowledge.indexOf(WAREHOUSE);

        // 公共口径是第一个注入的文档，位置 0
        assertThat(shared).isNotNegative();
        assertThat(product).isGreaterThan(shared);
        assertThat(purchase).isGreaterThan(product);
        assertThat(warehouse).isGreaterThan(purchase);
    }

    @Test
    void warehouseStaffOnlyGetsWarehouseProductAndReturnKnowledge() {
        // 仓管员的真实权限码组合（注意：没有 ai:assistant:use，进不了 AI 页面）
        String knowledge = provider.provide(List.of(
                "product:query", "warehouse:query", "warehouse:manage", "return:query",
                "dashboard:overview:query"));

        assertThat(knowledge).contains(WAREHOUSE, PRODUCT, RETURN, SHARED);
        assertThat(knowledge).doesNotContain(PURCHASE, SUPPLIER, SALES, CUSTOMER);
    }
}
