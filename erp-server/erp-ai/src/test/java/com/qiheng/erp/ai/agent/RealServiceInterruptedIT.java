package com.qiheng.erp.ai.agent;

import com.qiheng.erp.ai.clarification.ClarificationService;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.MainAgentConfiguration;
import com.qiheng.erp.ai.config.OpenAiCompatibleChatModel;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.sql.graph.SqlGraphService;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.conversation.ConversationExecutionLock;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.knowledge.graph.BusinessGraphProvider;
import com.qiheng.erp.ai.sql.tool.*;
import com.qiheng.erp.ai.dataset.tool.ReadDatasetTool;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.*;

/** 验证未配对工具调用后，用户新消息进入 ReactAgent 的实际路径。 */
@EnabledIfEnvironmentVariable(named="ERP_REAL_QWEN_IT", matches="true")
class RealServiceInterruptedIT {
 @Test void resumesWithInterruptedToolResult() throws Exception { inspect("继续，查询一号仓库"); }
 private void inspect(String newInput) throws Exception {
  var yaml=new YamlPropertiesFactoryBean(); yaml.setResources(new FileSystemResource(Path.of("..", ".env.ai.yaml")));
  var p=Objects.requireNonNull(yaml.getObject()); var thinking=p.getProperty("erp.ai.model.enable-thinking");
  var real=OpenAiCompatibleChatModel.create(p.getProperty("erp.ai.api-key"),p.getProperty("erp.ai.base-url"),p.getProperty("erp.ai.model.pro"),0.2,Duration.ofSeconds(60),thinking==null?null:Boolean.valueOf(thinking));
  var conf=new Config(); conf.useSingleServer().setAddress("redis://127.0.0.1:16327"); var redis=Redisson.create(conf);
  try {
   var saver=RedisSaver.builder().redisson(redis).stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new)).build();
   var conv=mock(AiConversationMapper.class); var users=mock(LoginUserService.class); var user=new LoginUser(); user.setPermissionCodes(List.of("ai:assistant:use")); when(users.findByUserId(1L)).thenReturn(user);
   long id=Math.abs(UUID.randomUUID().getLeastSignificantBits()/2); when(conv.selectOwned(id,1L)).thenReturn(new AiConversation());
   var graph=mock(BusinessGraphProvider.class); when(graph.render()).thenReturn("ERP仓库"); var sqlService=mock(SqlGraphService.class); when(sqlService.startForUser(anyLong(),anyLong(),anyString(), anyMap())).thenReturn(Map.of("status","FAILED","sqlRunId","test","message","测试环境无数据，停止查询")); var sql=new SqlQueryTool(sqlService); var ds=new ReadDatasetTool(mock(DatasetManager.class)); var f=new MainAgentConfiguration();
   var oldModel=mock(ChatModel.class); var tool=new AssistantMessage.ToolCall("ask-old","function","ask_user","{\"questions\":[{\"id\":\"w\",\"question\":\"哪个仓库？\",\"type\":\"TEXT\",\"options\":[]}]}");
   when(oldModel.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(tool)).build())))));
   var q1=new ClarificationService(conv,users,new ObjectMapper(),Duration.ofMillis(150)); var old=f.mainAgent(oldModel,saver,sql,ds,graph,q1); var thread="main:1:"+id;
   var config=RunnableConfig.builder().threadId(thread).addMetadata(TrustedToolContextInterceptor.TRUSTED_CONTEXT,new SqlToolContext(1L, id)).addMetadata(ClarificationService.EVENT_KEY,(java.util.function.Consumer<com.alibaba.cloud.ai.graph.NodeOutput>) e -> {}).build();
   assertThatThrownBy(()->old.stream("查询指定仓库库存",config).blockLast(Duration.ofSeconds(10))).hasMessageContaining("超时");
   var cp=saver.get(RunnableConfig.builder().threadId(thread).build()).orElseThrow();System.out.println("PROBE_OLD_NODE="+cp.getNextNodeId()+" OLD_MESSAGES="+cp.getState().get("messages"));
   var wrapped=mock(ChatModel.class); var count=new AtomicInteger(); var firstPrompt=new AtomicReference<List<Message>>(); when(wrapped.stream(any(Prompt.class))).thenAnswer(c->{Prompt in=c.getArgument(0); firstPrompt.compareAndSet(null, List.copyOf(in.getInstructions())); System.out.println("PROBE_MODEL_PROMPT="+in.getInstructions());count.incrementAndGet();return real.stream(in);});
   var q2 = new ClarificationService(conv, users, new ObjectMapper(), Duration.ofSeconds(3));
   var next = f.mainAgent(wrapped, saver, sql, ds, graph, q2);
   var main = new MainAgentService(next, saver, conv, users, new ConversationExecutionLock(redis), mock(com.qiheng.erp.ai.conversation.ConversationMessageStore.class), new com.fasterxml.jackson.databind.ObjectMapper());
   var earlyAsk = new AtomicInteger();
   var outputs = main.streamForUser(1L, id, newInput).doOnNext(event -> {
       if (count.get() == 0 && "ask_user".equals(event.node())) earlyAsk.incrementAndGet();
   }).collectList().block(Duration.ofSeconds(70));
   assertThat(outputs).isNotEmpty();
   assertThat(count.get()).isGreaterThan(0); assertThat(earlyAsk.get()).isZero(); assertThat(firstPrompt.get()).anyMatch(m -> m instanceof AssistantMessage a && a.getToolCalls().stream().anyMatch(t -> t.id().equals("ask-old"))); assertThat(firstPrompt.get()).anyMatch(m -> m instanceof UserMessage u && u.getText().equals(newInput)); assertThat(firstPrompt.get()).anyMatch(m -> m instanceof ToolResponseMessage t && t.getResponses().stream().anyMatch(r -> r.id().equals("ask-old") && r.responseData().contains("INTERRUPTED"))); var messages=firstPrompt.get(); int callIndex=-1, resultIndex=-1, userIndex=-1; for (int i=0;i<messages.size();i++) { var m=messages.get(i); if (m instanceof AssistantMessage a && a.getToolCalls().stream().anyMatch(t -> t.id().equals("ask-old"))) callIndex=i; if (m instanceof ToolResponseMessage t && t.getResponses().stream().anyMatch(r -> r.id().equals("ask-old"))) resultIndex=i; if (m instanceof UserMessage u && u.getText().equals(newInput)) userIndex=i; } assertThat(callIndex).isLessThan(resultIndex); assertThat(resultIndex).isLessThan(userIndex); System.out.println("PROBE_CASE="+newInput+" PROBE_MODEL_CALLS="+count.get());var end=saver.get(RunnableConfig.builder().threadId(thread).build()).orElseThrow();System.out.println("PROBE_END_NODE="+end.getNextNodeId()+" END_MESSAGES="+end.getState().get("messages"));
  } finally {redis.shutdown();}
 }
}
