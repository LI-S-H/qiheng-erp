package com.qiheng.erp.ai.sql.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.qiheng.erp.ai.sql.graph.node.SqlPrepareNode;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

class SqlPrepareDateTest {
    @Test
    void preservesTheTrustedRunDate() {
        var support = mock(SqlNodeSupport.class);
        when(support.refreshAuthorization(anyMap())).thenReturn(true);
        var result = new SqlPrepareNode(support).apply(new OverAllState(Map.of("sqlWork", Map.of("currentDate", "2026-11-05"))));
        assertThat(((Map<?, ?>) result.get("sqlWork")).get("currentDate")).isEqualTo("2026-11-05");
    }
}
