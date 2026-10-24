package com.qiheng.erp.ai.domain.vo;

/** 活动执行与当前请求的持久化结果共同确定公开状态。 */
public record RunStateVO(
        String status,
        String node,
        String label
) {

    public static final RunStateVO IDLE = new RunStateVO("IDLE", null, null);

    /** 运行状态。 */
    public RunStateVO(String node, String label) {
        this(node == null ? "IDLE" : "RUNNING", node, label);
    }

    /** 是否正在运行。 */
    public boolean running() {
        return "RUNNING".equals(status);
    }
}
