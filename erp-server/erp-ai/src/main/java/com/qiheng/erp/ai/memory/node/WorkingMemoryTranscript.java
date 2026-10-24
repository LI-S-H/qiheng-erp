package com.qiheng.erp.ai.memory.node;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

/**
 * 工作记忆的纯文本转写协议。
 *
 * <p>工作记忆表存的不是事件 JSON，而是「模型视角的转写」：一行 = 模型的一条消息，
 * 行首的标记说明这行的角色，正文与真正发给模型的内容一字不差。
 * 写侧靠标记写行，读侧靠标记还原消息，标记的知识只在本类一处。</p>
 *
 * <p>标记是幂等的：已经带标记的文本再标记一次不会叠加前缀，调用方不必关心
 * 上游是否已经加过标记。</p>
 *
 * @author Li
 * @since 2026-10-23
 */
public final class WorkingMemoryTranscript {

    /** 输入标记：模型这一轮收到的内容。 */
    public static final String INPUT = "[输入] ";

    /** 输出标记：模型这一轮返回的原文。 */
    public static final String OUTPUT = "[输出] ";

    /** 摘要标记：长对话被压缩后留下的摘要。 */
    public static final String SUMMARY = "[摘要] ";

    private WorkingMemoryTranscript() {
    }

    /**
     * 加输入标记。
     *
     * @param text 输入正文
     * @return 带输入标记的正文
     */
    public static String input(String text) {
        return mark(INPUT, text);
    }

    /**
     * 加输出标记。
     *
     * @param text 模型输出原文
     * @return 带输出标记的原文
     */
    public static String output(String text) {
        return mark(OUTPUT, text);
    }

    /**
     * 加摘要标记。
     *
     * @param text 摘要正文
     * @return 带摘要标记的正文
     */
    public static String summary(String text) {
        return mark(SUMMARY, text);
    }

    /**
     * 行内容 → 消息。
     *
     * <p>标记保留在消息正文里：模型看到的就是工作记忆里存的那一行，
     * 排查时能对着记忆复现模型当时看到了什么。</p>
     *
     * @param rowContent 工作记忆行正文
     * @return 消息；不是本协议的行返回 null，由调用方跳过
     */
    public static Message toMessage(String rowContent) {
        if (rowContent == null) {
            return null;
        }
        if (rowContent.startsWith(INPUT)) {
            return new UserMessage(rowContent);
        }
        if (rowContent.startsWith(OUTPUT) || rowContent.startsWith(SUMMARY)) {
            return new AssistantMessage(rowContent);
        }
        return null;
    }

    /**
     * 压缩摘要消息：官方 Hook 返回系统消息，转成带摘要标记的助手消息。
     *
     * @param text 摘要正文
     * @return 助手消息
     */
    public static Message summaryMessage(String text) {
        return new AssistantMessage(summary(text));
    }

    /**
     * 去掉行首标记。
     *
     * <p>模型会模仿历史行首的标记，把它一起写进返回内容（实测出现过
     * {@code [输出]{"needs":...}}）。解析前剥干净，这种回显就不至于被当成 JSON 语法错误；
     * 连着写了多个标记就连续剥。</p>
     *
     * @param text 原始文本，可为 null
     * @return 去掉前导标记并去掉首尾空白的结果
     */
    public static String strip(String text) {
        String body = text == null ? "" : text.trim();
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String marker : List.of(INPUT, OUTPUT, SUMMARY)) {
                String head = marker.trim();
                if (body.startsWith(head)) {
                    body = body.substring(head.length()).trim();
                    stripped = true;
                    break;
                }
            }
        }
        return body;
    }

    /**
     * 加标记；已经带同一标记的文本原样返回，不会叠加前缀。
     */
    private static String mark(String marker, String text) {
        String body = text == null ? "" : text;
        return body.startsWith(marker) ? body : marker + body;
    }
}
