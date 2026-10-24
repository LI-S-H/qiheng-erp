package com.qiheng.erp.ai.domain.model;

import java.util.List;

/**
 * 库里的索引事实：来自 information_schema 的结构事实。
 *
 * @param name    索引名
 * @param unique  是否唯一索引
 * @param columns 索引列，按索引内顺序
 * @author Li
 * @since 2026-10-21
 */
public record IndexFacts(String name, boolean unique, List<String> columns) {

    public IndexFacts {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
