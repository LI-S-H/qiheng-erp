package com.qiheng.erp.ai.service.data.registry;

import com.qiheng.erp.ai.service.data.schema.SchemaProvider;
import com.qiheng.erp.ai.service.data.schema.AuthorizedSchemaProvider;
import com.qiheng.erp.ai.service.data.registry.StubSchemaProvider;
import java.util.List;

/**
 * 测试用 Schema 提供者：返回固定的一段 Schema 文本。
 *
 * <p>图拓扑测试关心的是节点流转与暂停恢复，不是元数据组装；真实组装由
 * {@link AuthorizedSchemaProvider} 负责，它的测试单独覆盖。</p>
 */
public class StubSchemaProvider implements SchemaProvider {

    /** 固定的 Schema 文本，含一张表与一个主键。 */
    public static final String SCHEMA = """
            【DB_ID】 erp
            # Table: product, 产品表
            [
            (id:BIGINT, 产品ID, Primary Key)
            ]
            """;

    @Override
    public String provide(List<String> permissionCodes) {
        return SCHEMA;
    }
}
