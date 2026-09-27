package com.evops.common.opslog;

import java.io.PrintWriter;
import java.io.StringWriter;

/** 采集日志记录的辅助处理：堆栈裁剪为字符串。 */
final class OpsLogRecords {
    private OpsLogRecords() {
    }

    static String stackToString(Throwable t) {
        if (t == null) {
            return null;
        }
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
