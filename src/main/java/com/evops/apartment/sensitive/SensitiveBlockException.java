package com.evops.apartment.sensitive;

import java.util.List;

/**
 * 命中拦截级敏感词：业务写入必须终止。携带字段与定位信息，供接口返回与日志使用。
 */
public class SensitiveBlockException extends RuntimeException {
    private final String fieldPath;
    private final transient List<MatchHit> blockHits;

    public SensitiveBlockException(String fieldPath, List<MatchHit> blockHits) {
        super(buildMessage(fieldPath, blockHits));
        this.fieldPath = fieldPath;
        this.blockHits = blockHits;
    }

    public String getFieldPath() {
        return fieldPath;
    }

    public List<MatchHit> getBlockHits() {
        return blockHits;
    }

    private static String buildMessage(String fieldPath, List<MatchHit> hits) {
        StringBuilder words = new StringBuilder();
        for (MatchHit hit : hits) {
            if (words.length() > 0) {
                words.append("、");
            }
            words.append(hit.getWord()).append("(位置").append(hit.getStart())
                    .append("-").append(hit.getEnd()).append(")");
        }
        return "内容含违规词已拦截：字段[" + fieldPath + "]命中[" + words + "]，请删除后重试";
    }
}
