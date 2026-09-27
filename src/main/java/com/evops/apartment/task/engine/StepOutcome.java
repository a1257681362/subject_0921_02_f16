package com.evops.apartment.task.engine;

/**
 * 单个一致步骤的执行结果。
 */
public enum StepOutcome {
    /** 步骤完成并提交，进度 +5，继续下一步。 */
    ADVANCE,
    /** 步骤在等待外部条件（租客签署中）：任务退回 PENDING 并延后重领，进度不变。 */
    WAIT,
    /** 取消申请已在步骤边界生效：任务落 CANCELLED。 */
    CANCELLED
}
