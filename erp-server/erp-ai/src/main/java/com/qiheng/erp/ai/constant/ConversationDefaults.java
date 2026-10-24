package com.qiheng.erp.ai.constant;

/**
 * 会话默认字段：建会话时写入，也是判断标题是否被用户改过的依据。
 *
 * @author Li
 * @since 2026-10-16
 */
public final class ConversationDefaults {

    /** 新建会话的默认标题。 */
    public static final String TITLE = "新的经营会话";

    /** 新建会话的默认摘要。 */
    public static final String DESCRIPTION = "尚未开始分析";

    /** 自动标题取首条需求的前若干字，超出部分省略。 */
    public static final int AUTO_TITLE_LENGTH = 20;

    private ConversationDefaults() {
    }
}
