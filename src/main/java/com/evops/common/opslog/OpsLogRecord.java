package com.evops.common.opslog;

import java.time.LocalDateTime;

/**
 * 一条采集日志的不可变快照：在日志线程（logback appender）内构造，
 * 只带固定字段，随后投递给异步入库 Sink。
 */
public class OpsLogRecord {
    private final LocalDateTime logTime;
    private final String level;
    private final String loggerName;
    private final String threadName;
    private final String traceId;
    private final String contractNo;
    private final String username;
    private final String message;
    private final String stack;

    public OpsLogRecord(LocalDateTime logTime, String level, String loggerName, String threadName,
                        String traceId, String contractNo, String username,
                        String message, String stack) {
        this.logTime = logTime;
        this.level = level;
        this.loggerName = loggerName;
        this.threadName = threadName;
        this.traceId = traceId;
        this.contractNo = contractNo;
        this.username = username;
        this.message = message;
        this.stack = stack;
    }

    public LocalDateTime getLogTime() { return logTime; }
    public String getLevel() { return level; }
    public String getLoggerName() { return loggerName; }
    public String getThreadName() { return threadName; }
    public String getTraceId() { return traceId; }
    public String getContractNo() { return contractNo; }
    public String getUsername() { return username; }
    public String getMessage() { return message; }
    public String getStack() { return stack; }
}
