package com.evops.apartment.sensitive;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * 文本归一化：把“变形词”还原成 DFA 可匹配的形式，同时保留 归一化位置 → 原文位置 的映射，
 * 命中后可把结果回映到原文（位置可定位、打码覆盖插空字符）。
 *
 * 归一化步骤（按序）：
 * 1. 按 Unicode 码点逐字 NFKC：全角字母数字/兼容字符折叠为半角（ＡＢＣ、１２３、㈱、ﬁ→fi 等）；
 *    必须按码点而不是 char 处理——星面字符（如数学粗体 𝐜，U+1D41C）是代理对，
 *    逐 char 归一化会把高/低代理位分别折叠失败而整体丢弃，等于给英文词开了一个绕过口子；
 * 2. 剥离零宽/不可见字符（ZWSP/ZWNJ/ZWJ/WJ/BOM/软连字符/MVS/变体选择符），
 *    以及“看着是空白、JDK 却归类为字母(Lo)”的韩文填充符（U+115F/U+1160/U+3164，
 *    半角 U+FFA0 经 NFKC 折叠为 U+1160）；
 * 3. 大小写折叠（ABC → abc）；
 * 4. 形近/数字替换（0→o、1→i、3→e、4→a、5→s、7→t、@→a、$→s）；
 * 5. 丢弃其余所有非字母/数字字符（空格、标点、符号等“插空”分隔符）。
 *
 * 因此「代-开 发·票」「赌ㅤ博」（ㅤ=零宽韩文填充符）这类插空写法在归一化后都等于
 * 「代开发票」「赌博」，DFA 照常命中；命中区间在原文中是
 * [origin[normStart], origin[normEnd-1]+charCount)，中间的插空字符与完整代理对全部落在区间内，
 * 打码时一并遮蔽且不会留下半个代理对。逐码点 NFKC 保证映射表直接指向原始输入下标
 * （整串 NFKC 会改变长度，无法定位）。
 */
public final class TextNormalizer {
    /** U+200B ZWSP / U+200C ZWNJ / U+200D ZWJ / U+2060 WORD JOINER。 */
    private static final int ZWSP = 0x200B;
    private static final int ZWNJ = 0x200C;
    private static final int ZWJ = 0x200D;
    private static final int WORD_JOINER = 0x2060;
    /** U+FEFF BOM/零宽不换行空格、U+00AD 软连字符、U+180E 蒙古文元音分隔符。 */
    private static final int BOM = 0xFEFF;
    private static final int SOFT_HYPHEN = 0x00AD;
    private static final int MONGOLIAN_SEP = 0x180E;
    /** 变体选择符 VS1..VS16（U+FE00..U+FE0F）与补充区 VS17..VS256（U+E0100..U+E01EF）。 */
    private static final int VS_FIRST = 0xFE00;
    private static final int VS_LAST = 0xFE0F;
    private static final int VS_SUPP_FIRST = 0xE0100;
    private static final int VS_SUPP_LAST = 0xE01EF;
    /**
     * 韩文填充符：视觉零宽（等同空格），JDK 8 却归类为 Lo 字母（isLetterOrDigit=true），
     * 不特判剥离就会被当成有效字符而绕过匹配。
     * U+FFA0 半角填充符会被 NFKC 折叠为 U+1160，这里一并显式列出。
     */
    private static final int HANGUL_CHOSEONG_FILLER = 0x115F;
    private static final int HANGUL_JUNGSEONG_FILLER = 0x1160;
    private static final int HANGUL_FILLER = 0x3164;
    private static final int HALFWIDTH_HANGUL_FILLER = 0xFFA0;

    private TextNormalizer() {
    }

    /** 归一化后的文本与逐字位置映射：map[i] 为归一化第 i 字在原始输入中的 char 下标（代理对记高代理位）。 */
    public static Normalized normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Normalized.EMPTY;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        List<Integer> positions = new ArrayList<>(raw.length());
        int i = 0;
        while (i < raw.length()) {
            int codePoint = raw.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            // 零宽/填充字符直接剥离，不参与折叠
            if (isInvisible(codePoint)) {
                i += charCount;
                continue;
            }
            // 逐码点 NFKC：兼容字符（全角/连字/星面数学字母等）折叠，可能 1→多字符；都映射到同一个原下标 i
            String folded = Normalizer.normalize(new String(Character.toChars(codePoint)),
                    Normalizer.Form.NFKC);
            for (int j = 0; j < folded.length(); j++) {
                char fc = folded.charAt(j);
                if (isInvisible(fc)) {
                    continue;
                }
                char c = Character.toLowerCase(fc);
                c = leetReplace(c);
                if (Character.isLetterOrDigit(c)) {
                    sb.append(c);
                    positions.add(i);
                }
            }
            i += charCount;
        }
        int[] map = new int[positions.size()];
        for (int k = 0; k < map.length; k++) {
            map[k] = positions.get(k);
        }
        return new Normalized(sb.toString(), map);
    }

    /** 词典词条同样走一遍归一化，保证与文本同口径。 */
    public static String normalizeWord(String word) {
        return normalize(word).getText();
    }

    private static boolean isInvisible(int codePoint) {
        if (codePoint == ZWSP || codePoint == ZWNJ || codePoint == ZWJ || codePoint == WORD_JOINER
                || codePoint == BOM || codePoint == SOFT_HYPHEN || codePoint == MONGOLIAN_SEP
                || codePoint == HANGUL_CHOSEONG_FILLER || codePoint == HANGUL_JUNGSEONG_FILLER
                || codePoint == HANGUL_FILLER || codePoint == HALFWIDTH_HANGUL_FILLER) {
            return true;
        }
        return (codePoint >= VS_FIRST && codePoint <= VS_LAST)
                || (codePoint >= VS_SUPP_FIRST && codePoint <= VS_SUPP_LAST);
    }

    /** 视觉混淆字符归一：数字/符号 → 形似字母。仅影响匹配与位置映射，不改写原文落库值。 */
    private static char leetReplace(char c) {
        switch (c) {
            case '0': return 'o';
            case '1': return 'i';
            case '3': return 'e';
            case '4': return 'a';
            case '5': return 's';
            case '7': return 't';
            case '@': return 'a';
            case '$': return 's';
            default: return c;
        }
    }
}
