package com.qiheng.erp.ai.graph.support;

/**
 * 从模型输出里取出 JSON 载荷。
 *
 * <p>即使要求"只输出 JSON"，模型偶尔仍会先写一段分析再给 JSON。解析前把第一个
 * {@code &#123;} 到最后一个 {@code &#125;} 之间的内容截出来，能救回这类输出；纯文字、没有 JSON 的
 * 输出原样返回，交给格式修复去兜。</p>
 *
 * @author Li
 * @since 2026-10-24
 */
public final class JsonPayload {

    private JsonPayload() {
    }

    /**
     * 取出 JSON 载荷。
     *
     * @param text 模型原始输出，可为 null
     * @return 第一个花括号到最后一个花括号之间的内容；没有花括号时返回原文
     */
    public static String unwrap(String text) {
        String body = text == null ? "" : text;
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return body;
        }
        return body.substring(start, end + 1);
    }
}