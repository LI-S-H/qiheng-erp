package com.qiheng.erp.ai.constant;

/** 主图状态键。节点只返回局部更新，不自行维护另一份运行状态。 */
public final class GraphStateKeys {

    public static final String USER_ID = "user_id";

    /** 本次输入的请求 ID，将检查点与本轮消息、节点记忆关联。 */
    public static final String REQUEST_ID = "request_id";
    public static final String USER_REQUEST = "user_request";
    public static final String CLARIFICATION_HISTORY = "clarification_history";

    /** planner 本轮的模型原始输出，需求数组协议的 JSON 文本。 */
    public static final String PLANNER_OUTPUT = "planner_output";

    /**
     * 方案子图产出的需求草案（JSON 文本），与 {@link #PLANNER_OUTPUT} 并列构成草案的两个来源。
     *
     * <p>两个来源串行产生，{@code need_check} / {@code need_review} 读有值的那份，
     * 审阅通过后两份一起清空（消费完成）。哪份有值就是谁产的，失败回流据此回谁。</p>
     */
    public static final String PROPOSAL_OUTPUT = "proposal_output";

    /** 已通过结构校验并被接受的需求数组；元素是 Need 的 JSON 兼容结构。 */
    public static final String NEEDS = "needs";

    /** 需求结构校验结论，同时作为条件边路由值。 */
    public static final String NEED_CHECK_RESULT = "need_check_result";

    /** 业务完整性审阅结论，含 verdict 与 issues 原文，回 planner 时作为反馈。 */
    public static final String NEED_REVIEW_RESULT = "need_review_result";

    /** 需求选择结论，同时作为条件边路由值。 */
    public static final String NEED_SELECTOR_RESULT = "need_selector_result";

    /** need_review 自身的输出格式错误，只回本节点重问，不回 planner。 */
    public static final String NEED_REVIEW_ERROR = "need_review_error";

    /** 结构校验与业务审阅共用的修复计数；任一环节通过后归零。 */
    public static final String REPAIR_COUNT = "repair_count";

    /**
     * 业务完整性审阅的回流计数。
     *
     * <p>与格式修复分开计数：两种回流的失败模式不同——格式错是模型没按协议输出，
     * 业务漏查是模型按协议输出了但内容不全。后者一次修不完是常态（真实模型实测
     * 要两轮才能补齐），共用一份预算会误杀合法的多轮修复。</p>
     */
    public static final String REVIEW_COUNT = "review_count";

    /** 最近一次修复的具体错误或问题清单，作为下一轮 planner 的反馈。 */
    public static final String REPAIR_ERROR = "repair_error";

    /** 业务完整性审阅提出的问题清单，回 planner 时逐条带给它。 */
    public static final String REPAIR_ISSUES = "repair_issues";

    /** need_review 自身的输出原文，格式修复时喂回本节点。 */
    public static final String NEED_REVIEW_OUTPUT = "need_review_output";

    /** 当前需求编号，由需求选择节点写入。 */
    public static final String CURRENT_NEED_ID = "current_need_id";

    /** planner 运行模式：INITIAL 首轮拆解 / REFINE 反馈补充，由应用服务写入。 */
    public static final String PLANNER_MODE = "planner_mode";

    /** 本轮任务内 planner 被调用的次数，planner 每调用一次 +1。 */
    public static final String ROUND_NO = "round_no";

    /** 审阅反馈出的缺口清单，REFINE 轮读它。 */
    public static final String REFINE_GAPS = "refine_gaps";

    /** 已确认且已验证可行的候选方案，方案子图据此展开 D 段需求时读取。 */
    public static final String CONFIRMED_PROPOSALS = "confirmed_proposals";

    public static final String HUMAN_FEEDBACK = "human_feedback";

    /** 本次暂停的澄清问题，由澄清发布拍写入，人工节点消费后清空。 */
    public static final String CLARIFICATION_QUESTIONS = "clarification_questions";

    /** 澄清暂停标识：暂停拍节点生成并写入，恢复时校验并消费，与框架检查点编号解耦。 */
    public static final String PAUSE_ID = "pause_id";

    /** 当前任务归属的会话 ID,用于节点记忆与服务端落库。 */
    public static final String CONVERSATION_ID = "conversation_id";

    /** 本轮已公开发布的产物；发布拍节点写入，进度追踪器据此转发内容事件，采用 REPLACE 策略。 */
    public static final String PUBLISHED_ARTIFACT = "published_artifact";

    /**
     * 当前用户的权限码，服务端从会话解析后写入，客户端不可声明。
     * 超管是 ["*"]，其余是多角色权限码并集；data_access 节点据此算出可见表。
     */
    public static final String PERMISSION_CODES = "permission_codes";

    /**
     * 授权 Schema 快照：data_access 节点组装后写入，后续节点直接读，不必重复查询。
     * 内容含 Schema 文本、可见表清单与指纹；固定在一轮任务内，权限中途变化不影响本轮。
     */
    public static final String AUTHORIZED_SCHEMA_REF = "authorized_schema_ref";

    /**
     * 授权业务口径：knowledge 节点按权限码装配后写入。
     * 回答"这些数怎么算才对"；与授权 Schema 同一套模块推导，不会出现口径与表错配。
     */
    public static final String BUSINESS_KNOWLEDGE = "business_knowledge";

    public static final String PLANNER_NODE = "planner";
    public static final String NEED_CHECK_NODE = "need_check";
    public static final String NEED_REVIEW_NODE = "need_review";
    public static final String NEED_SELECTOR_NODE = "need_selector";
    public static final String HUMAN_FEEDBACK_NODE = "human_feedback";

    /**
     * 记忆压缩的进度节点：不是图节点，只在摘要模型调用前后发一对进度事件，
     * 让用户在长时间压缩期间看到"正在压缩历史记忆"，而不是界面无响应。
     */
    public static final String MEMORY_COMPACT_NODE = "memory_compact";

    /** 数据范围节点：按权限码组装可见 Schema 文本。 */
    public static final String DATA_ACCESS_NODE = "data_access";

    /** 业务口径节点：按权限码装配业务规则文本。 */
    public static final String KNOWLEDGE_NODE = "knowledge";

    private GraphStateKeys() {
    }
}
