package com.evops.apartment.task.engine;

/**
 * 步骤执行结果：正常推进 / 等待外部条件 / 已在边界取消。
 * resultJson 仅在最后一步完成时由处理器给出。
 */
public class StepResult {
    private final StepOutcome outcome;
    private final String resultJson;

    private StepResult(StepOutcome outcome, String resultJson) {
        this.outcome = outcome;
        this.resultJson = resultJson;
    }

    public static StepResult advance() {
        return new StepResult(StepOutcome.ADVANCE, null);
    }

    /** 最后一步：携带成功产物摘要。 */
    public static StepResult finished(String resultJson) {
        return new StepResult(StepOutcome.ADVANCE, resultJson);
    }

    public static StepResult waiting() {
        return new StepResult(StepOutcome.WAIT, null);
    }

    public static StepResult cancelled() {
        return new StepResult(StepOutcome.CANCELLED, null);
    }

    public StepOutcome getOutcome() { return outcome; }
    public String getResultJson() { return resultJson; }
}
