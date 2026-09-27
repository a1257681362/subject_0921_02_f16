package com.evops.apartment.sensitive;

/**
 * 词典条目（引擎内部不可变快照单元）。word 存储时已归一化。
 */
public final class DictionaryEntry {
    private final Long id;
    private final String normalizedWord;
    private final String category;
    private final int actionLevel;

    public DictionaryEntry(Long id, String normalizedWord, String category, int actionLevel) {
        this.id = id;
        this.normalizedWord = normalizedWord;
        this.category = category;
        this.actionLevel = actionLevel;
    }

    public Long getId() {
        return id;
    }

    public String getNormalizedWord() {
        return normalizedWord;
    }

    public String getCategory() {
        return category;
    }

    public int getActionLevel() {
        return actionLevel;
    }
}
