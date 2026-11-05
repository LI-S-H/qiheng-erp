package com.qiheng.erp.ai.sql.graph.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.sql.graph.model.output.SqlGenerationOutput;
import com.qiheng.erp.ai.sql.graph.model.output.SqlInspectionOutput;
import com.qiheng.erp.ai.sql.graph.model.output.SqlModelOutputConverter;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** SQL 生成和结果检查共用的模型调用及 JSON 输出处理。 */
public final class SqlModelClient {
    private final ChatModel model;
    private final ObjectMapper mapper;
    private final SqlModelOutputConverter converter;

    /** 注入模型及 JSON 工具，用于生成和检查节点的共同调用。 */
    public SqlModelClient(ChatModel model, ObjectMapper mapper) {
        this.model = model;
        this.mapper = mapper;
        this.converter = new SqlModelOutputConverter(mapper);
    }

    /** 为两个生成分支注入各自的官方 Schema，并解析成对应类型。 */
    public SqlGenerationOutput generate(String instruction, Map<String, Object> input) throws JsonProcessingException {
        return converter.readGeneration(ask(instruction + "\n\n" + converter.generationFormat(), input));
    }

    /** 为检查结果注入官方格式说明，并验证检查决定。 */
    public SqlInspectionOutput inspect(String instruction, Map<String, Object> input) throws JsonProcessingException {
        return converter.readInspection(ask(instruction + "\n\n" + converter.inspectionFormat(), input));
    }

    /** 只调用模型；网络故障不当作模型输出格式错误。 */
    private String ask(String instruction, Map<String, Object> input) throws JsonProcessingException {
        var response = model.call(new Prompt(List.of(new SystemMessage(instruction),
                new UserMessage(mapper.writeValueAsString(input)))));
        return response.getResult().getOutput().getText();
    }

    /** 启动时加载固定提示词，缺失视为配置错误。 */
    public static String loadPrompt(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("SQL 提示词无法加载：" + path, error);
        }
    }
}
