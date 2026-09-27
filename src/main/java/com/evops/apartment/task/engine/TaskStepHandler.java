package com.evops.apartment.task.engine;

import com.evops.apartment.task.entity.AsyncTask;

/**
 * 一种后台任务类型对应一个步骤处理器：声明类型、总步骤数，并执行指定序号的一致步骤。
 */
public interface TaskStepHandler {

    /** 与 {@link AsyncTask#getTaskType()} 对应。 */
    String type();

    /** 一致步骤总数（进度 = stepNo * 100 / totalSteps）。 */
    int totalSteps();

    /**
     * 执行第 stepNo 步（1 基）。步骤体必须幂等——引擎在失败/宕机重跑时会重新执行未确认完成的步骤。
     */
    StepResult executeStep(AsyncTask task, int stepNo);
}
