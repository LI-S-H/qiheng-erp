package com.qiheng.erp.ai.sql.registry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.sql.schema.AuthorizedSchemaProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import java.util.ArrayList;
import java.util.List;

/** 使用本地账号的实际角色权限，只读观察授权 Schema 的长度和规模。 */
@SpringJUnitConfig(SchemaProviderMysqlIntegrationTest.TestConfig.class)
@EnabledIfEnvironmentVariable(named = "ERP_ACCOUNT_SCHEMA_TEST", matches = "true")
class RealAccountSchemaReadOnlyIT {
    @Autowired private AuthorizedSchemaProvider provider;
    @Autowired private JdbcTemplate jdbc;

    /** 仅统计元数据，不读取业务数据，也不发送模型请求。 */
    @Test
    void measuresAuthorizedSchemaForExistingAccounts() throws Exception {
        var mapper = new ObjectMapper();
        for (String name : List.of("admin", "manager01", "purchase01", "sales01", "warehouse01")) {
            var rows = jdbc.queryForList("""
                    SELECT u.is_admin, r.permission_codes FROM sys_user u
                    LEFT JOIN sys_user_role ur ON ur.user_id = u.id
                    LEFT JOIN sys_role r ON r.id = ur.role_id AND r.status = 1 AND r.deleted = 0
                    WHERE u.username = ? AND u.status = 1 AND u.deleted = 0
                    """, name);
            List<String> permissions = new ArrayList<>();
            if (!rows.isEmpty() && ((Number) rows.getFirst().get("is_admin")).intValue() == 1) {
                permissions.add("*");
            } else {
                for (var row : rows) {
                    Object codes = row.get("permission_codes");
                    if (codes != null) permissions.addAll(mapper.readValue(codes.toString(), new TypeReference<List<String>>() {}));
                }
            }
            var snapshot = provider.provideFreshSnapshot(permissions);
            int columns = snapshot.columns().values().stream().mapToInt(List::size).sum();
            long softDeleted = snapshot.tables().stream().filter(t -> t.logicalDelete()).count();
            System.out.printf("SCHEMA_ACCOUNT name=%s tables=%d columns=%d characters=%d soft_delete_tables=%d%n",
                    name, snapshot.tables().size(), columns, snapshot.schemaText().length(), softDeleted);
        }
    }

}
