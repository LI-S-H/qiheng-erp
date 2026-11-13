package com.qiheng.erp.ai.agent;

/**
 * 识别框架把模型调用异常转成的答复文本。
 *
 * <p>框架的模型节点捕获模型调用异常后，会把它转成一条内容为 {@code Exception: 原始原因}
 * 的助手消息，图继续跑到结束节点，调用本身不抛异常，检查点也已结束。
 * 因此只能按文本识别，识别后由调用方决定如何展示与记录。</p>
 *
 * @author Li
 * @since 2026-11-13
 */
public final class AgentModelFailure {

    /** 框架生成的失败文本前缀；原因可能为空，不能依赖其后必须有空格。 */
    private static final String PREFIX = "Exception:";

    /** 模型失败时返回给用户的说明，不带框架内部原文。 */
    public static final String RESULT_MESSAGE = "模型服务暂时不可用，请稍后重试。";

    /** 模型失败时给前端失败事件的错误码。 */
    public static final String ERROR_CODE = "MODEL_CALL_FAILED";

    private AgentModelFailure() {
    }

    /**
     * 判断答复是否为模型调用失败产生的文本。
     *
     * @param text 模型答复原文
     * @return 是失败文本返回 true
     */
    public static boolean isFailure(String text) {
        return text != null && text.strip().startsWith(PREFIX);
    }

    /**
     * 取出失败文本中的原始原因，去掉框架前缀。
     *
     * @param text 模型答复原文
     * @return 原始原因；不是失败文本时返回原文
     */
    public static String reason(String text) {
        if (!isFailure(text)) {
            return text == null ? "" : text;
        }
        String reason = text.strip().substring(PREFIX.length()).strip();
        return reason.isEmpty() ? "模型服务未返回内容" : reason;
    }
}