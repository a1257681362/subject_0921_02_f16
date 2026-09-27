package com.evops.apartment.report;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * apt-8 签约结果 Word 报告配置。
 */
@ConfigurationProperties(prefix = "evops.sign-report")
public class ReportProperties {
    /** 报告归档根目录（相对工作目录或绝对路径）；文件按 小区/楼栋 分目录落盘，只新建不覆盖。 */
    private String archiveDir = "archive/sign-reports";
    /** poi-tl 模板的 classpath 位置。 */
    private String templateClasspath = "templates/sign-result.docx";

    public String getArchiveDir() { return archiveDir; }
    public void setArchiveDir(String archiveDir) { this.archiveDir = archiveDir; }
    public String getTemplateClasspath() { return templateClasspath; }
    public void setTemplateClasspath(String templateClasspath) { this.templateClasspath = templateClasspath; }
}
