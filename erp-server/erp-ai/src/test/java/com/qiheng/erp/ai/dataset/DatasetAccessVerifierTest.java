package com.qiheng.erp.ai.dataset;

import com.qiheng.erp.ai.config.properties.AiSqlProperties;
import com.qiheng.erp.ai.domain.model.AuthorizedSchemaSnapshot;
import com.qiheng.erp.ai.sql.schema.SchemaProvider;
import com.qiheng.erp.ai.sql.guard.SqlGuard;
import com.qiheng.erp.ai.sql.guard.model.GuardResult;
import com.qiheng.erp.ai.sql.guard.model.SqlGuardRejectCode;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatasetAccessVerifierTest {
    @Test
    void checksCurrentPermissionsAndRejectsRevokedAccess() {
        DatasetRepository repository = mock(DatasetRepository.class);
        LoginUserService users = mock(LoginUserService.class);
        SchemaProvider schemas = mock(SchemaProvider.class);
        SqlGuard guard = mock(SqlGuard.class);
        AiSqlProperties properties = new AiSqlProperties(null, true, 200, 5000, 10, 10);
        var verifier = new DatasetAccessVerifier(repository, users, schemas, guard, properties);
        var record = new DatasetRecord("ds_1", "run", 10, 20, "库存", List.of(),
                1, true, "READY", null, null);
        LoginUser user = new LoginUser();
        user.setPermissionCodes(List.of("warehouse:query", "ai:assistant:use"));
        when(users.findByUserId(10L)).thenReturn(user);
        when(repository.findSourceSql("ds_1", 10, 20)).thenReturn(Optional.of("SELECT quantity FROM warehouse_stock"));
        var snapshot = AuthorizedSchemaSnapshot.empty();
        when(schemas.provideFreshSnapshot(List.of("warehouse:query", "ai:assistant:use"))).thenReturn(snapshot);
        when(guard.check(anyString(), eq(snapshot), any(), eq(false)))
                .thenReturn(new GuardResult.Ok(null), new GuardResult.Rejected(SqlGuardRejectCode.TABLE_NOT_ALLOWED, "无权限"));
        verifier.verify(record);
        assertThrows(IllegalStateException.class, () -> verifier.verify(record));
        verify(users, times(2)).findByUserId(10L);
        verify(schemas, times(2)).provideFreshSnapshot(List.of("warehouse:query", "ai:assistant:use"));
        user.setPermissionCodes(List.of("warehouse:query"));
        assertEquals("当前用户无 AI 使用权限", assertThrows(IllegalStateException.class, () -> verifier.verify(record)).getMessage());
        user.setPermissionCodes(List.of("*"));
        when(schemas.provideFreshSnapshot(List.of("*"))).thenReturn(snapshot);
        when(guard.check(anyString(), eq(snapshot), any(), eq(false))).thenReturn(new GuardResult.Ok(null));
        assertDoesNotThrow(() -> verifier.verify(record));
    }
}
