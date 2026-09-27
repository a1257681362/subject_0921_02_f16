package com.evops.apartment.pinyin;

/**
 * 姓名拼音冗余索引值：全拼变体字段 + 首字母变体字段。
 * 字段形如 {@code |zhangxueyou|zhang xue you|}，{@code null} 表示该姓名无可用拼音（空姓名）。
 */
public final class PinyinIndex {
    public static final PinyinIndex EMPTY = new PinyinIndex(null, null);

    private final String full;
    private final String initials;

    public PinyinIndex(String full, String initials) {
        this.full = full;
        this.initials = initials;
    }

    public String getFull() {
        return full;
    }

    public String getInitials() {
        return initials;
    }
}
