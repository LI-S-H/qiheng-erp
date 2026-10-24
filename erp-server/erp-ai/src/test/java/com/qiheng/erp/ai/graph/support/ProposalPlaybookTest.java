package com.qiheng.erp.ai.graph.support;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 专家知识库的契约测试。
 *
 * <p>验证真实知识资源能完整加载，以及占位小节仍不会注入模型。</p>
 */
class ProposalPlaybookTest {

    @Test
    void allExpertFilesLoadWithUniqueRulesAndDecisionBoundaries() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:proposal/*.md");
        assertThat(Arrays.stream(resources).map(Resource::getFilename).sorted().toList())
                .containsExactly("clearance.md", "common.md", "demand.md", "promotion.md",
                        "purchase.md", "replenishment.md", "supplier_selection.md", "transfer.md");

        ProposalPlaybook playbook = new ProposalPlaybook();
        assertThat(playbook.isEmpty()).isFalse();
        String content = playbook.content();
        List<String> ids = Pattern.compile("(?m)^(?:COM|DEM|REP|PUR|SUP|TRF|PRM|CLR)-\\d{3}")
                .matcher(content).results().map(result -> result.group()).toList();
        assertThat(ids).doesNotHaveDuplicates().hasSize(31);
        assertThat(ids).containsSubsequence("CLR-001", "COM-001", "DEM-001", "PRM-001",
                "PUR-001", "REP-001", "SUP-001", "TRF-001");
        // 不只检查文件存在：清洗后仍必须保留公式与限制，避免模型只读到规则标题。
        assertThat(content)
                .contains("Q100×Pcent/100", "quantity−outbound_qty", "max(0,F_t-C_t)",
                        "Q*=√(2DK/h)", "周期服务概率也不是销量满足率", "不执行 SQL")
                .doesNotContain("待补", "# 专家知识", "> 版本", "> 核对日期");
    }

    @Test
    void placeholderOnlySectionsAreEmpty() {
        assertThat(new ProposalPlaybook("# 专家知识\n> 说明\n## 通用\n（待补）\n").isEmpty()).isTrue();
    }

    @Test
    void filledSectionsAreKeptAndPlaceholdersSkipped() {
        String raw = """
                # 专家知识 · 调拨
                > 说明性引用不进正文
                ## 通用
                （待补）
                ## 调拨
                调出后调出仓自身的可用库存不得低于其安全库存
                ## 组合
                先调后采，各段数量之和等于总缺口
                """;

        ProposalPlaybook playbook = new ProposalPlaybook(raw);

        assertThat(playbook.isEmpty()).isFalse();
        assertThat(playbook.content())
                .contains("调拨：")
                .contains("调出仓自身")
                .contains("组合：")
                .contains("先调后采")
                .doesNotContain("通用")
                .doesNotContain("待补")
                .doesNotContain("说明性引用");
    }

    @Test
    void blankInputYieldsEmptyContent() {
        assertThat(new ProposalPlaybook("").isEmpty()).isTrue();
        assertThat(new ProposalPlaybook(null).isEmpty()).isTrue();
    }
}
