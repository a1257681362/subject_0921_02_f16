package com.evops.common.opslog;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 运维日志采集行（apt-6）：字段与 logback 固定格式一一对应，
 * 管家按合同号/TraceId 在「公寓楼-运维日志」菜单检索，一次请求的全部日志可聚合。
 * 保留 14 天：每日定时删除 log_time 早于 14 天前的记录。
 */
@TableName("t_ops_log_entry")
public class OpsLogEntry {
    @TableId(type = IdType.AUTO)
    private Long id;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private LocalDateTime logTime;
    private String level;
    private String loggerName;
    private String threadName;
    private String traceId;
    private String contractNo;
    private String username;
    private String message;
    private String stack;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDateTime getLogTime() { return logTime; }
    public void setLogTime(LocalDateTime logTime) { this.logTime = logTime; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getLoggerName() { return loggerName; }
    public void setLoggerName(String loggerName) { this.loggerName = loggerName; }
    public String getThreadName() { return threadName; }
    public void setThreadName(String threadName) { this.threadName = threadName; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getContractNo() { return contractNo; }
    public void setContractNo(String contractNo) { this.contractNo = contractNo; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getStack() { return stack; }
    public void setStack(String stack) { this.stack = stack; }
}
