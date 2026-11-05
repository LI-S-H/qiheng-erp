package com.qiheng.erp.ai.clarification;

import java.util.List;

/** 仅供集成测试注入可信用户，生产答案入口仍从登录会话取身份。 */
public final class ClarificationTestAccess {
    private ClarificationTestAccess() {}

    public static void answer(ClarificationService service, long userId, long conversationId,
                              String id, List<ClarificationAnswer> answers, String note) {
        service.answerForUser(userId, conversationId, id, answers, note);
    }
}
