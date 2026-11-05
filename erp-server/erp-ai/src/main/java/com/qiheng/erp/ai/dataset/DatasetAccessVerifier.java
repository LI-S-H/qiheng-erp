package com.qiheng.erp.ai.dataset;

import com.qiheng.erp.ai.config.properties.AiSqlProperties;
import com.qiheng.erp.ai.sql.schema.SchemaProvider;
import com.qiheng.erp.ai.sql.scope.PermissionCodes;
import com.qiheng.erp.ai.sql.guard.SqlGuard;
import com.qiheng.erp.ai.sql.guard.model.GuardResult;
import com.qiheng.erp.ai.sql.guard.model.SqlGuardLimits;
import com.qiheng.erp.security.service.LoginUserService;
import org.springframework.stereotype.Component;

/** 数据集读取前重新按当前用户的表字段权限校验来源 SQL。 */
@Component
class DatasetAccessVerifier implements DatasetAccess {
    private final DatasetRepository repository;
    private final LoginUserService users;
    private final SchemaProvider schemas;
    private final SqlGuard guard;
    private final SqlGuardLimits limits;

    DatasetAccessVerifier(DatasetRepository repository, LoginUserService users, SchemaProvider schemas,
                          SqlGuard guard, AiSqlProperties properties) {
        this.repository = repository;
        this.users = users;
        this.schemas = schemas;
        this.guard = guard;
        this.limits = new SqlGuardLimits(properties.maxTotalRows());
    }

    @Override
    public void verify(DatasetRecord record) {
        var user = users.findByUserId(record.userId());
        if (user == null) throw new IllegalStateException("用户不存在，无法访问数据集");
        var permissions = PermissionCodes.of(user);
        if (!permissions.contains("*") && !permissions.contains("ai:assistant:use")) {
            throw new IllegalStateException("当前用户无 AI 使用权限");
        }
        String sql = repository.findSourceSql(record.datasetId(), record.userId(), record.conversationId())
                .orElseThrow(() -> new IllegalStateException("数据集查询来源不存在"));
        var snapshot = schemas.provideFreshSnapshot(permissions);
        if (!(guard.check(sql, snapshot, limits, false) instanceof GuardResult.Ok)) {
            throw new IllegalStateException("当前用户无权访问此数据集");
        }
    }
}
