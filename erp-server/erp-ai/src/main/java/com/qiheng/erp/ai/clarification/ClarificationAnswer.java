package com.qiheng.erp.ai.clarification;

import java.util.List;

/** 用户对单个追问的选择和自由补充。 */
public record ClarificationAnswer(String questionId, List<String> selectedOptions, String customText) {}
