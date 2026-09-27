package com.evops.apartment.sensitive;

import java.util.Collections;
import java.util.List;

/**
 * 单字段扫描结果：全部命中、是否拦截、打码后的文本（无 MASK 命中时等于原文）。
 */
public final class ScanResult {
    private final boolean blocked;
    private final List<MatchHit> hits;
    private final String maskedText;

    ScanResult(boolean blocked, List<MatchHit> hits, String maskedText) {
        this.blocked = blocked;
        this.hits = hits;
        this.maskedText = maskedText;
    }

    static ScanResult clean(String text) {
        return new ScanResult(false, Collections.emptyList(), text);
    }

    public boolean isBlocked() {
        return blocked;
    }

    public List<MatchHit> getHits() {
        return hits;
    }

    public String getMaskedText() {
        return maskedText;
    }
}
