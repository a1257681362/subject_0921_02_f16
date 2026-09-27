package com.evops.apartment.sensitive;

/**
 * 归一化结果：归一化文本 + 逐字映射回原文的下标表。
 * map[i] = 归一化文本第 i 字对应在（NFKC 折叠后的）原文中的位置。
 */
public final class Normalized {
    static final Normalized EMPTY = new Normalized("", new int[0]);

    private final String text;
    private final int[] map;

    Normalized(String text, int[] map) {
        this.text = text;
        this.map = map;
    }

    public String getText() {
        return text;
    }

    public int length() {
        return text.length();
    }

    /** 归一化位置 → 原文位置。 */
    public int originIndex(int normalizedIndex) {
        return map[normalizedIndex];
    }
}
