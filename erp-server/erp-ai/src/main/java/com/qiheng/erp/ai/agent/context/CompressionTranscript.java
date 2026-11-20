package com.qiheng.erp.ai.agent.context;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 把待摘要的消息渲染成摘要模型可读的文本，并在超出预算时逐级降级。
 *
 * <p>降级顺序与 opencode 一致：先完整输出；放不下时从最老的一轮开始截断工具结果，
 * 一级降完才降下一级；仍放不下才替换掉工具结果、只保留用户文本，最后整轮省略。
 * 最新一轮永远不整轮省略。</p>
 *
 * @author Li
 * @since 2026-11-20
 */
public final class CompressionTranscript {

    /** 摘要消息前缀，与 {@link ContextCompressionHook#SUMMARY_PREFIX} 保持一致。 */
    public static final String SUMMARY_PREFIX = "【前情摘要】";

    /** 降级层级数：完整、截断工具结果、省略工具结果、只留用户文本、整轮省略。 */
    private static final int LEVELS = 5;

    /** 整轮省略所在层级。 */
    private static final int OMITTED_LEVEL = LEVELS - 1;

    /** 只保留用户文本所在层级。 */
    private static final int USER_ONLY_LEVEL = LEVELS - 2;

    private CompressionTranscript() {
    }

    /** 渲染结果：摘要输入文本、纳入的消息条数、整轮省略的轮次数。 */
    public record Result(String text, int summarizedMessages, int omittedExchanges) {
    }

    /**
     * 渲染摘要输入。
     *
     * @param messages       待摘要消息，按时间顺序
     * @param budgetTokens   输入 token 预算
     * @param toolClipChars  工具结果前后各保留的字符数
     * @param messageClipChars 参数或用户文本的截断字符数
     * @return 渲染结果；连最新一轮都放不下时返回 null
     *
     * <p>开头已有的「【前情摘要】」视为上次压缩留下的摘要，单独放在转录最前，
     * 不参与轮次降级；其余消息才按轮次逐级缩短。</p>
     */
    public static Result render(List<Message> messages, int budgetTokens, int toolClipChars, int messageClipChars) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        // 已有摘要在最前面，单独作为后续摘要的输入，不参与轮次降级。
        boolean hasLead = isSummary(messages.getFirst());
        String lead = hasLead ? renderLead(messages.getFirst()) : "";
        List<Message> history = messages.subList(hasLead ? 1 : 0, messages.size());
        if (history.isEmpty()) {
            return null;
        }
        List<List<Message>> exchanges = groupByExchange(history);
        List<String[]> renders = new ArrayList<>(exchanges.size());
        int[][] sizes = new int[exchanges.size()][LEVELS];
        for (int index = 0; index < exchanges.size(); index++) {
            String[] levels = renderLevels(exchanges.get(index), toolClipChars, messageClipChars);
            renders.add(levels);
            for (int level = 0; level < LEVELS; level++) {
                sizes[index][level] = TokenEstimator.estimate(levels[level]);
            }
        }
        int[] levels = new int[exchanges.size()];
        int leadSize = TokenEstimator.estimate(lead);
        int total = leadSize + sum(sizes, levels);
        int newest = exchanges.size() - 1;
        // 所有轮次降到同一级后才降下一级，细节从最老的历史开始丢。
        for (int level = 1; level < LEVELS && total > budgetTokens; level++) {
            for (int index = 0; index < exchanges.size() && total > budgetTokens; index++) {
                if (index == newest && renders.get(index)[level].isEmpty()) {
                    continue;
                }
                total += sizes[index][level] - sizes[index][levels[index]];
                levels[index] = level;
            }
        }
        // 逐级降完仍放不下，整轮丢弃最老的历史，但保留最新一轮和已有摘要。
        int start = 0;
        while (total > budgetTokens && start < newest) {
            total -= sizes[start][levels[start]];
            start++;
        }
        if (total > budgetTokens) {
            return null;
        }
        // 丢弃的轮次和降到整轮省略层的轮次都算省略，日志里据此判断丢了多少历史。
        int omitted = start;
        for (int index = start; index < exchanges.size(); index++) {
            if (levels[index] == OMITTED_LEVEL) {
                omitted++;
            }
        }
        StringBuilder text = new StringBuilder();
        if (!lead.isEmpty()) {
            text.append(lead).append("\n\n");
        }
        if (omitted > 0) {
            text.append("[更早的 ").append(omitted).append(" 个轮次已省略]\n\n");
        }
        for (int index = start; index < exchanges.size(); index++) {
            if (renders.get(index)[levels[index]].isEmpty()) {
                continue;
            }
            text.append(renders.get(index)[levels[index]]).append("\n\n");
        }
        return new Result(text.toString().strip(), messages.size(), omitted);
    }

    /** 判断一条消息是否为上一次压缩留下的摘要。 */
    private static boolean isSummary(Message message) {
        return message instanceof UserMessage user && user.getText() != null
                && user.getText().startsWith(SUMMARY_PREFIX);
    }

    /** 把旧摘要渲染成摘要请求的开头，永远完整保留。 */
    private static String renderLead(Message message) {
        String text = message.getText();
        String body = text.substring(SUMMARY_PREFIX.length()).strip();
        return body.isEmpty() ? "[已有摘要]" : "[已有摘要]:\n" + body;
    }

    /** 一条用户消息及其后的回复算一轮。 */
    private static List<List<Message>> groupByExchange(List<Message> messages) {
        List<List<Message>> exchanges = new ArrayList<>();
        for (Message message : messages) {
            if (message instanceof UserMessage || exchanges.isEmpty()) {
                exchanges.add(new ArrayList<>());
            }
            exchanges.getLast().add(message);
        }
        return exchanges;
    }

    /** 为一轮对话生成五个层级的渲染文本，整轮省略层留空。 */
    private static String[] renderLevels(List<Message> exchange, int toolClipChars, int messageClipChars) {
        String[] levels = new String[LEVELS];
        for (int level = 0; level < LEVELS; level++) {
            if (level == OMITTED_LEVEL) {
                levels[level] = "";
                continue;
            }
            StringBuilder text = new StringBuilder();
            for (Message message : exchange) {
                String line = renderMessage(message, level, toolClipChars, messageClipChars);
                if (line.isEmpty()) {
                    continue;
                }
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(line);
            }
            levels[level] = text.toString();
        }
        return levels;
    }

    /** 按层级渲染单条消息；返回空串表示该层级下省略。 */
    private static String renderMessage(Message message, int level, int toolClipChars, int messageClipChars) {
        if (message instanceof SystemMessage) {
            return "";
        }
        if (message instanceof UserMessage user) {
            String text = user.getText();
            if (text == null || text.isBlank()) {
                return "";
            }
            return "[用户]: " + (level >= USER_ONLY_LEVEL ? clip(text, messageClipChars) : text);
        }
        if (level >= USER_ONLY_LEVEL) {
            return "";
        }
        if (message instanceof AssistantMessage assistant) {
            return renderAssistant(assistant, level, messageClipChars);
        }
        if (message instanceof ToolResponseMessage response) {
            return renderToolResponse(response, level, toolClipChars);
        }
        return "";
    }

    /** 渲染助手文本与工具调用；第二级起截断调用参数。 */
    private static String renderAssistant(AssistantMessage assistant, int level, int messageClipChars) {
        StringBuilder text = new StringBuilder();
        String content = assistant.getText();
        if (content != null && !content.isBlank()) {
            text.append("[助手]: ").append(content);
        }
        for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            String arguments = call.arguments() == null ? "" : call.arguments();
            text.append("[助手工具调用]: ").append(call.name()).append('(')
                    .append(level >= 2 ? clip(arguments, messageClipChars) : arguments).append(')');
        }
        return text.toString();
    }

    /** 渲染工具结果；第一级起截断，第二级起替换为省略标记。 */
    private static String renderToolResponse(ToolResponseMessage response, int level, int toolClipChars) {
        StringBuilder text = new StringBuilder();
        for (ToolResponseMessage.ToolResponse item : response.getResponses()) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append("[工具结果 ").append(item.name()).append("]: ");
            if (level >= 2) {
                text.append("[工具结果已省略]");
            } else {
                String data = item.responseData() == null ? "" : item.responseData();
                text.append(level >= 1 ? clip(data, toolClipChars) : data);
            }
        }
        return text.toString();
    }

    /** 保留文本前后各指定字符数，中间注明省略的字符数。 */
    private static String clip(String value, int half) {
        int keep = Math.max(1, half) * 2;
        if (value.length() <= keep) {
            return value;
        }
        int omitted = value.length() - keep;
        return value.substring(0, half) + "\n[… " + omitted + " 个字符已省略 …]\n"
                + value.substring(value.length() - half);
    }

    /** 按当前层级统计总 token。 */
    private static int sum(int[][] sizes, int[] levels) {
        int total = 0;
        for (int index = 0; index < levels.length; index++) {
            total += sizes[index][levels[index]];
        }
        return total;
    }
}