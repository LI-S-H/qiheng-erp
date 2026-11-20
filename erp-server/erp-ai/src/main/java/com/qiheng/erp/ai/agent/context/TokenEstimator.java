package com.qiheng.erp.ai.agent.context;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

/**
 * 面向中文对话的 token 估算。
 *
 * <p>框架自带的计数器按固定字符数取整，中文会低估三到四倍，压缩时机偏晚。
 * 这里按字符类别分别估算：中日韩字符约一字一 token，其他字符约四字一 token。</p>
 *
 * @author Li
 * @since 2026-11-20
 */
public final class TokenEstimator {

    /** 每条消息的固定结构开销。 */
    private static final int MESSAGE_OVERHEAD = 4;

    /** 每个工具调用或工具响应的固定结构开销。 */
    private static final int TOOL_OVERHEAD = 8;

    /** 其他字符折算 token 时的分母。 */
    private static final int ASCII_CHARS_PER_TOKEN = 4;

    private TokenEstimator() {
    }

    /**
     * 估算一组消息的 token 数。
     *
     * @param messages 消息列表
     * @return 估算 token 数；空列表为 0
     */
    public static int estimate(List<? extends Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Message message : messages) {
            total += estimate(message);
        }
        return total;
    }

    /**
     * 估算单条消息的 token 数。
     *
     * @param message 消息
     * @return 估算 token 数
     */
    public static int estimate(Message message) {
        if (message == null) {
            return 0;
        }
        int total = MESSAGE_OVERHEAD + estimate(message.getText());
        // 工具调用的名称和参数同样进入模型输入，不能漏算。
        if (message instanceof AssistantMessage assistant) {
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                total += TOOL_OVERHEAD + estimate(call.name()) + estimate(call.arguments());
            }
        }
        if (message instanceof ToolResponseMessage response) {
            for (ToolResponseMessage.ToolResponse item : response.getResponses()) {
                total += TOOL_OVERHEAD + estimate(item.responseData());
            }
        }
        return total;
    }

    /**
     * 估算一段文本的 token 数。
     *
     * @param text 文本
     * @return 估算 token 数；空文本为 0
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (isCjk(value)) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (other + ASCII_CHARS_PER_TOKEN - 1) / ASCII_CHARS_PER_TOKEN;
    }

    /** 判断字符是否属于中日韩文字或全角符号。 */
    private static boolean isCjk(char value) {
        return value >= 0x2E80 && value <= 0x9FFF
                || value >= 0xF900 && value <= 0xFAFF
                || value >= 0xFE30 && value <= 0xFE4F
                || value >= 0xFF00 && value <= 0xFFEF;
    }
}