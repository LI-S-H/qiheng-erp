package com.qiheng.erp.ai.service.data.sql.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * wall 拒因翻译的契约：每种常见拒因都要给出一句模型能照做的中文。
 *
 * @author Li
 * @since 2026-10-26
 */
class WallRejectionReasonsTest {

    private static final String PREFIX =
            "sql injection violation, dbType mysql, druid-version 1.2.28, ";

    @Test
    void explainsComment() {
        assertThat(WallRejectionReasons.explain(PREFIX + "comment not allow : SELECT 1 -- x"))
                .contains("不能写注释");
    }

    @Test
    void explainsMultiStatement() {
        assertThat(WallRejectionReasons.explain(PREFIX + "multi-statement not allow : SELECT 1; SELECT 2"))
                .contains("一次只能执行一条语句");
    }

    @Test
    void explainsSelectStar() {
        assertThat(WallRejectionReasons.explain(PREFIX + "'SELECT *' not allow : SELECT * FROM t"))
                .contains("SELECT *");
    }

    @Test
    void explainsWriteStatement() {
        assertThat(WallRejectionReasons.explain(PREFIX + "update not allow : UPDATE t SET a = 1"))
                .contains("只能查询");
        assertThat(WallRejectionReasons.explain(PREFIX + "delete not allow : DELETE FROM t"))
                .contains("只能查询");
    }

    @Test
    void explainsOutfile() {
        assertThat(WallRejectionReasons.explain(PREFIX + "select into outfile not allow : SELECT 1"))
                .contains("服务器文件");
    }

    @Test
    void explainsAlwaysTrueWhere() {
        assertThat(WallRejectionReasons.explain(PREFIX + "select where alway true not allow : SELECT 1"))
                .contains("WHERE 1=1");
    }

    @Test
    void unknownReasonFallsBackWithReasonText() {
        assertThat(WallRejectionReasons.explain(PREFIX + "something new not allow : SELECT 1"))
                .contains("something new not allow");
    }

    @Test
    void sqlTextDoesNotMisclassifyUnknownReason() {
        // SQL 里出现 updated_at 不能被当成写操作：只匹配原因段
        String message = PREFIX + "limit not allow : SELECT id FROM t WHERE updated_at > '2026-01-01'";

        assertThat(WallRejectionReasons.explain(message))
                .contains("limit not allow")
                .doesNotContain("只能查询");
    }

    @Test
    void emptyMessageIsHandled() {
        assertThat(WallRejectionReasons.explain(null)).isNotBlank();
        assertThat(WallRejectionReasons.explain("  ")).isNotBlank();
    }
}