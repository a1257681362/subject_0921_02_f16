package com.evops.apartment.sensitive;

/**
 * 一次业务写入的敏感词扫描上下文：定位“哪个模块、哪笔业务、谁提交的、哪个请求”。
 */
public final class SensitiveContext {
    public static final String MODULE_PROPERTY = "PROPERTY";
    public static final String MODULE_LEASE = "LEASE";
    public static final String MODULE_CHECKOUT = "CHECKOUT";
    public static final String MODULE_HANDOVER = "HANDOVER";

    private final String module;
    private final String bizRef;
    private final String requestNo;
    private final String operator;
    private final Long sourceId;

    private SensitiveContext(Builder b) {
        this.module = b.module;
        this.bizRef = b.bizRef;
        this.requestNo = b.requestNo;
        this.operator = b.operator;
        this.sourceId = b.sourceId;
    }

    public static Builder builder(String module, String operator) {
        return new Builder(module, operator);
    }

    public String getModule() { return module; }
    public String getBizRef() { return bizRef; }
    public String getRequestNo() { return requestNo; }
    public String getOperator() { return operator; }
    public Long getSourceId() { return sourceId; }

    public static final class Builder {
        private final String module;
        private final String operator;
        private String bizRef;
        private String requestNo;
        private Long sourceId;

        private Builder(String module, String operator) {
            this.module = module;
            this.operator = operator;
        }

        public Builder bizRef(String bizRef) { this.bizRef = bizRef; return this; }
        public Builder requestNo(String requestNo) { this.requestNo = requestNo; return this; }
        public Builder sourceId(Long sourceId) { this.sourceId = sourceId; return this; }

        public SensitiveContext build() {
            return new SensitiveContext(this);
        }
    }
}
