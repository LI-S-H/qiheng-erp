package com.qiheng.erp.ai.graph.support;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 专家知识库：按决策类型分文件存放，供方案子图与业务审阅读取。
 *
 * <p><b>专家知识是经验，不是模型的产出。</b>补货、调拨、采购这类方案真正要权衡的方面
 * （调出仓自身够不够、到货时效、最小起订量、组合顺序……）是固定知识。让 planner 现场写，
 * 它写不全也不稳定；所以它单独成文，由消费者按需读取：</p>
 *
 * <ul>
 *   <li><b>方案子图</b>：通用 + 它自己选中的动作类型对应的文档；</li>
 *   <li><b>need_review</b>：全量——审方案派生的需求时按它逐条对照。</li>
 * </ul>
 *
 * <p><b>不注入 {@code PROPOSAL} 项的 instruction</b>：instruction 会随需求存进 State，
 * 每轮都渲染进 planner 的输入，塞一大段知识既费 token 又稀释注意力。</p>
 *
 * <p>文件放在 {@code resources/proposal/}，按文件名顺序拼接；只有「待补」占位的文件
 * 会自动跳过，内容填好后生效，不必再改代码。</p>
 *
 * @author Li
 * @since 2026-10-23
 */
public class ProposalPlaybook {

    /** 知识文件目录。 */
    private static final String RESOURCE_PATTERN = "classpath*:proposal/*.md";

    /** 占位标记：小节正文只有含它的行时视为未填。 */
    private static final String PLACEHOLDER = "待补";

    /** 清洗后的全部维度清单；为空表示还没填内容。 */
    private final String content;

    public ProposalPlaybook() {
        this(readAll());
    }

    /**
     * 用给定文本构造：测试与"内容还没定"的场景都能用。
     *
     * <p>同样会清洗：跳过说明性行与只有占位的小节。</p>
     *
     * @param raw 维度清单原文，可为空
     */
    public ProposalPlaybook(String raw) {
        this.content = clean(raw);
    }

    /** 维度清单是否可用（内容填了才可用）。 */
    public boolean isEmpty() {
        return content.isEmpty();
    }

    /** 维度清单原文：审阅节点把它当判据输入。 */
    public String content() {
        return content;
    }

    /**
     * 读目录下全部知识文件，按文件名排序拼接。
     */
    private static String readAll() {
        StringBuilder all = new StringBuilder();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(RESOURCE_PATTERN);
            Arrays.sort(resources, Comparator.comparing(resource -> String.valueOf(resource.getFilename())));
            for (Resource resource : resources) {
                String cleaned = clean(resource.getContentAsString(StandardCharsets.UTF_8));
                if (!cleaned.isEmpty()) {
                    all.append(cleaned).append("\n\n");
                }
            }
        } catch (IOException error) {
            return "";
        }
        return all.toString().strip();
    }

    /**
     * 清洗一份知识文件：跳过说明性行与只有占位的小节，把小节标题转成纯文本。
     */
    private static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        List<String> lines = raw.lines().toList();
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < lines.size()) {
            String line = lines.get(index).strip();
            if (line.startsWith("## ")) {
                int next = index + 1;
                StringBuilder body = new StringBuilder();
                while (next < lines.size() && !lines.get(next).strip().startsWith("## ")) {
                    body.append(lines.get(next)).append('\n');
                    next++;
                }
                String text = body.toString().strip();
                // 只有占位的小节不算填了
                if (filled(text)) {
                    out.append(line.substring(3)).append("：\n").append(text).append('\n');
                }
                index = next;
                continue;
            }
            // 文档标题与说明性引用不进入正文
            if (!line.startsWith("# ") && !line.startsWith(">")) {
                out.append(line).append('\n');
            }
            index++;
        }
        return out.toString().strip();
    }

    /** 小节正文是否填了内容：至少有一行不含占位标记。 */
    private static boolean filled(String body) {
        for (String line : body.lines().toList()) {
            String stripped = line.strip();
            if (!stripped.isEmpty() && !stripped.contains(PLACEHOLDER)) {
                return true;
            }
        }
        return false;
    }
}