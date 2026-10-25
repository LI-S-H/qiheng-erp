package com.qiheng.erp.ai.service.knowledge;

import com.qiheng.erp.ai.service.data.scope.DataScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 授权业务知识提供者：按权限码决定注入哪些业务规则文档。
 *
 * <p>推导逻辑与 {@code AuthorizedSchemaProvider} 完全同源（共用 {@link DataScope}）——
 * 权限码的动作必须是 query 或 manage，模块前缀就是登记表里的 {@code module_code}：</p>
 *
 * <pre>
 *   purchase:query  → purchase  → knowledge/purchase.md
 *   warehouse:query → warehouse → knowledge/warehouse.md
 *   supplier:query  → supplier  → knowledge/supplier.md
 *   *               → 全部模块
 * </pre>
 *
 * <p>这样"能查哪些表"与"该知道哪些口径"永远来自同一套模块划分，
 * 不会出现查得到表却没有对应口径、或者给了口径却查不到表的错配。</p>
 *
 * <p>公共口径（数量放大 100 倍、金额按分、状态机的通用读法）单独成文，
 * 对所有用户都注入——它是读懂任何一张表的前提。</p>
 *
 * @author Li
 * @since 2026-10-22
 */
@Slf4j
@Component
public class AuthorizedKnowledgeProvider implements KnowledgeProvider {

    /** 业务规则文档的 classpath 目录。 */
    private static final String KNOWLEDGE_DIR = "knowledge/";

    /** 公共口径文档，人人注入。 */
    private static final String SHARED_MODULE = "shared";

    /**
     * 业务模块的固定顺序：先看主数据，再看交易，最后看仓储。
     *
     * <p>顺序影响模型对规则的理解——"产品精度"排在"采购入库"前面，
     * 模型读到入库口径时已经有了精度的概念。</p>
     */
    private static final List<String> MODULE_ORDER = List.of(
            SHARED_MODULE, "product", "customer", "supplier", "purchase", "sales", "return", "warehouse");

    /** 已加载的文档内容：模块 → 文本。文件是静态资源，读一次就够。 */
    private final Map<String, String> documents = new ConcurrentHashMap<>();

    @Override
    public String provide(List<String> permissionCodes) {
        Set<String> modules = resolveModules(permissionCodes);
        if (modules.isEmpty()) {
            return "";
        }
        StringBuilder knowledge = new StringBuilder();
        for (String module : MODULE_ORDER) {
            if (!modules.contains(module)) {
                continue;
            }
            String document = load(module);
            if (!document.isBlank()) {
                knowledge.append(document).append("\n\n");
            }
        }
        return knowledge.toString().trim();
    }

    /**
     * 权限码 → 业务模块集合。
     *
     * <p>与 Schema 的模块推导同源（{@link DataScope}）：动作必须是 query 或 manage；
     * 没有对应业务规则文档的模块（{@code ai}、{@code system}、{@code dashboard}）自然被过滤掉。</p>
     */
    private Set<String> resolveModules(List<String> permissionCodes) {
        DataScope scope = DataScope.of(permissionCodes);
        Set<String> modules = new LinkedHashSet<>(scope.modules());
        if (scope.admin()) {
            modules.addAll(MODULE_ORDER);
        }
        // 公共口径是读懂任何一张表的前提，人人注入
        modules.add(SHARED_MODULE);
        return modules;
    }

    /**
     * 加载一份业务规则文档；文件不存在时返回空串（该模块没有对应口径）。
     */
    private String load(String module) {
        return documents.computeIfAbsent(module, key -> {
            ClassPathResource resource = new ClassPathResource(KNOWLEDGE_DIR + key + ".md");
            if (!resource.exists()) {
                return "";
            }
            try {
                return resource.getContentAsString(StandardCharsets.UTF_8);
            } catch (IOException error) {
                log.warn("业务规则文档读取失败 module={}", key, error);
                return "";
            }
        });
    }
}