package com.evops.apartment.sensitive;

/**
 * 一次命中：命中的词条（归一化形式）、类别、处置级别，以及在原文中的定位区间。
 * 区间为 [start, end)，按原文 char 偏移计（0 基，end 排他）；
 * 插空命中时，起止之间被剥离的分隔符也包含在区间内，打码可一并遮蔽。
 */
public final class MatchHit {
    private final String word;
    private final String category;
    private final int actionLevel;
    private final int start;
    private final int end;

    MatchHit(String word, String category, int actionLevel, int start, int end) {
        this.word = word;
        this.category = category;
        this.actionLevel = actionLevel;
        this.start = start;
        this.end = end;
    }

    public String getWord() {
        return word;
    }

    public String getCategory() {
        return category;
    }

    public int getActionLevel() {
        return actionLevel;
    }

    public int getStart() {
        return start;
    }

    public int getEnd() {
        return end;
    }
}
