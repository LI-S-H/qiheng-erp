package com.qiheng.erp.ai.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 核实框架失败文本的识别口径。
 *
 * <p>框架把模型异常转成 {@code Exception: 原始原因} 的助手消息，图继续跑到结束节点；
 * 因此只能按文本识别，且必须把原始原因完整取出，供流水与失败说明使用。</p>
 */
class AgentModelFailureTest {

    /** 框架生成的失败文本被识别，并取出原始原因。 */
    @Test
    void recognizesFrameworkFailureTextAndKeepsReason() {
        assertThat(AgentModelFailure.isFailure("Exception: 上游超时，未返回内容")).isTrue();
        assertThat(AgentModelFailure.reason("Exception: 上游超时，未返回内容")).isEqualTo("上游超时，未返回内容");
    }

    /** 前后空白不影响识别，原因去掉首尾空白。 */
    @Test
    void ignoresSurroundingWhitespace() {
        assertThat(AgentModelFailure.isFailure("\n  Exception: 连接失败  ")).isTrue();
        assertThat(AgentModelFailure.reason("\n  Exception: 连接失败  ")).isEqualTo("连接失败");
    }

    /** 正常答复与空文本都不算失败。 */
    @Test
    void treatsNormalAnswerAsSuccess() {
        assertThat(AgentModelFailure.isFailure("库存充足")).isFalse();
        assertThat(AgentModelFailure.isFailure("")).isFalse();
        assertThat(AgentModelFailure.isFailure(null)).isFalse();
    }

    /** 失败文本没有原因时给出兜底说明，避免出现空原因。 */
    @Test
    void fallsBackWhenReasonIsEmpty() {
        assertThat(AgentModelFailure.reason("Exception:   ")).isEqualTo("模型服务未返回内容");
    }

    /** 非失败文本原样返回，便于调用方统一取值。 */
    @Test
    void returnsOriginalTextForSuccess() {
        assertThat(AgentModelFailure.reason("库存充足")).isEqualTo("库存充足");
        assertThat(AgentModelFailure.reason(null)).isEmpty();
    }
}