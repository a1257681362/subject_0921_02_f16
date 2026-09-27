package com.evops.apartment.sensitive;

/**
 * 敏感词处置分级。词典按词配置级别，扫描时按命中词的级别处置。
 */
public final class WordAction {
    private WordAction() {
    }

    /** 1 级：命中后自动打码，业务照常提交。 */
    public static final int MASK = 1;
    /** 2 级：命中即拦截，业务写入整体失败（拦截痕迹独立事务保留）。 */
    public static final int BLOCK = 2;

    public static boolean valid(int level) {
        return level == MASK || level == BLOCK;
    }
}
