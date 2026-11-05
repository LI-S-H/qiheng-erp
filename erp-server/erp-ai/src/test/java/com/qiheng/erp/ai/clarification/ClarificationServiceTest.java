package com.qiheng.erp.ai.clarification;

import com.qiheng.erp.ai.clarification.ClarificationService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.clarification.AskUserTool.Question;
import com.qiheng.erp.ai.clarification.AskUserTool.QuestionType;
import com.qiheng.erp.ai.clarification.ClarificationAnswer;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClarificationServiceTest {
    @Test void deliversAnswerWithoutWaitingForConversationLock() throws Exception {
        var conversations = mock(AiConversationMapper.class);
        when(conversations.selectOwned(2L, 1L)).thenReturn(new AiConversation());
        var users = mock(LoginUserService.class);
        var user = new LoginUser(); user.setPermissionCodes(List.of("ai:assistant:use"));
        when(users.findByUserId(1L)).thenReturn(user);
        var service = new ClarificationService(conversations, users, new ObjectMapper(), Duration.ofSeconds(2));
        var event = new CompletableFuture<String>();
        var output = CompletableFuture.supplyAsync(() -> {
            try { return service.ask(1, 2, List.of(new Question("w", "仓库？", QuestionType.TEXT, List.of())),
                    value -> event.complete((String) value.state().data().get("questionRequestId"))); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        String id = event.get(2, TimeUnit.SECONDS);
        user.setUserId(1L);
        try (var context = mockStatic(com.qiheng.erp.security.context.UserContext.class)) {
            context.when(com.qiheng.erp.security.context.UserContext::requireCurrentUser).thenReturn(user);
            service.answerForCurrentUser(2, id, List.of(new ClarificationAnswer("w", List.of(), "一号仓")), "本月");
        }
        assertThat(output.get(2, TimeUnit.SECONDS)).contains("一号仓", "本月");
        assertThatThrownBy(() -> service.answerForUser(1, 2, id, List.of(), null)).isInstanceOf(IllegalStateException.class);
    }

    @Test void timeoutStopsWaitingAndClearsRequest() throws Exception {
        var service = new ClarificationService(mock(AiConversationMapper.class), mock(LoginUserService.class), new ObjectMapper(), Duration.ofMillis(20));
        assertThatThrownBy(() -> service.ask(1, 2, List.of(new Question("w", "仓库？", QuestionType.TEXT, List.of())), value -> {}))
                .hasMessageContaining(ClarificationService.TIMEOUT_MARKER);
    }
}
