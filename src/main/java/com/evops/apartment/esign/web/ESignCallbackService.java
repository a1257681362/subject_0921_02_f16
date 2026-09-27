package com.evops.apartment.esign.web;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.apartment.esign.ESignClient;
import com.evops.apartment.task.engine.SignArchiveStepHandler;
import com.evops.apartment.task.entity.AsyncTask;
import com.evops.apartment.task.mapper.AsyncTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台「签署完成」回调业务处理（安全校验已在 {@link ESignCallbackFilter} 完成，本类只谈业务）：
 *
 * - 按 bizNo（EVOPS-合同号）定位签约归档任务；
 * - 通知电子签章提供方签署已完成（mock 翻转内存流程；真实平台下一次轮询即 SIGNED）；
 * - 即时唤醒停在第 12 步「等待租客签署」的 PENDING 任务：把 next_run_at 提到当前，
 *   下一轮派发立即领取继续盖章/归档，不再等满轮询间隔；
 * - 回调丢失或任务正在 RUNNING/已越过等待步：轮询路径照常兜底，回调天然幂等，重复到达安全。
 */
@Service
public class ESignCallbackService {
    private static final Logger log = LoggerFactory.getLogger(ESignCallbackService.class);

    /** 等待租客签署：已提交完前 11 个一致步骤（进度 55%）。 */
    private static final int STEP_WAITING_SIGN = 11;
    /** 签署完成事件类型；报文字段缺省（空值）也按签署完成处理。 */
    private static final String EVENT_SIGN_COMPLETED = "SIGN_COMPLETED";

    private final AsyncTaskMapper taskMapper;
    private final ESignClient eSignClient;

    public ESignCallbackService(AsyncTaskMapper taskMapper, ESignClient eSignClient) {
        this.taskMapper = taskMapper;
        this.eSignClient = eSignClient;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> handleSignCompleted(ESignSignCallbackRequest req) {
        if (req == null || req.getBizNo() == null || req.getBizNo().trim().isEmpty()) {
            throw new IllegalArgumentException("回调缺少业务号 bizNo");
        }
        if (req.getEvent() != null && !req.getEvent().trim().isEmpty()
                && !EVENT_SIGN_COMPLETED.equals(req.getEvent().trim())) {
            throw new IllegalArgumentException("不支持的回调事件类型: " + req.getEvent());
        }
        String bizNo = req.getBizNo().trim();
        String contractNo = contractNoOf(bizNo);

        // 同合同进行中任务唯一（提交接口已拦重复）；这里取最新一条，兼容极少见的历史任务并存
        List<AsyncTask> tasks = taskMapper.selectList(new QueryWrapper<AsyncTask>()
                .eq("task_type", AsyncTask.TYPE_SIGN_ARCHIVE)
                .eq("biz_ref", contractNo)
                .in("status", Arrays.asList(AsyncTask.STATUS_PENDING, AsyncTask.STATUS_RUNNING))
                .orderByDesc("id")
                .last("LIMIT 1"));
        if (tasks.isEmpty()) {
            // 已终态（成功/失败/取消）或不存在：签名虽真，但没有可推进的等待任务。
            // 明确拒绝以便平台/排障侧发现错配，而不是静默吞掉。
            throw new IllegalArgumentException("回调业务号无进行中的签约归档任务: " + bizNo);
        }
        AsyncTask task = tasks.get(0);

        // 提供方状态翻转（mock）；真实平台默认空实现。bizNo/flowNo 不匹配会抛异常，业务拒绝。
        eSignClient.applySignCompletedCallback(bizNo, req.getFlowNo());

        // 仅 PENDING 且停在等待步（11）能被即时唤醒；RUNNING 中本轮查询之后自然看见 SIGNED，
        // 已越过等待步说明轮询先行，回调幂等不回退。
        int woken = taskMapper.wakeWaitingForSign(contractNo, LocalDateTime.now());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("received", true);
        result.put("bizNo", bizNo);
        result.put("contractNo", contractNo);
        result.put("taskNo", task.getTaskNo());
        result.put("woken", woken == 1);
        log.info("电子签章签署完成回调已受理 bizNo={} contractNo={} taskNo={} currentStep={} 即时唤醒={}",
                bizNo, contractNo, task.getTaskNo(), task.getCurrentStep(), woken == 1);
        return result;
    }

    /** bizNo = EVOPS-合同号（见 {@link SignArchiveStepHandler#toBizNo}），反解合同号。 */
    private String contractNoOf(String bizNo) {
        String prefix = "EVOPS-";
        if (!bizNo.startsWith(prefix) || bizNo.length() <= prefix.length()) {
            throw new IllegalArgumentException("回调业务号格式非法，期望 " + prefix + "合同号: " + bizNo);
        }
        return bizNo.substring(prefix.length());
    }
}
