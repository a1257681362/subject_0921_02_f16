package com.evops.apartment.sensitive;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DFA 敏感词引擎单元测试：归一化、插空命中、位置回映、变形词、最长匹配与重叠去重、分级。
 */
class DfaEngineTest {

    private DfaMatcher matcher(String... words) {
        List<DictionaryEntry> entries = new java.util.ArrayList<>();
        int id = 1;
        for (String w : words) {
            entries.add(new DictionaryEntry((long) id, TextNormalizer.normalizeWord(w),
                    "ILLEGAL", WordAction.BLOCK));
            id++;
        }
        return new DfaMatcher(entries);
    }

    @Test
    void plainHit_andPosition() {
        DfaMatcher m = matcher("赌博");
        List<MatchHit> hits = m.match("这里有赌博活动");
        assertEquals(1, hits.size());
        assertEquals("赌博", hits.get(0).getWord());
        assertEquals(3, hits.get(0).getStart());
        assertEquals(5, hits.get(0).getEnd());
    }

    @Test
    void noHit() {
        DfaMatcher m = matcher("赌博");
        assertTrue(m.match("小区一切正常").isEmpty());
    }

    @Test
    void separatorInsertion_stillHits_andRangeCoversSeparators() {
        DfaMatcher m = matcher("代开发票");
        // 符号插空
        List<MatchHit> hits = m.match("代-开发票");
        assertEquals(1, hits.size());
        assertEquals(0, hits.get(0).getStart());
        assertEquals(5, hits.get(0).getEnd(), "区间需覆盖首尾之间的插空字符 '-'");

        // 空格/标点全部插空
        hits = m.match("代 开.发*票");
        assertEquals(1, hits.size());
        assertEquals(0, hits.get(0).getStart());
        assertEquals(7, hits.get(0).getEnd());
    }

    @Test
    void zeroWidthInsertion_stillHits() {
        DfaMatcher m = matcher("加微信");
        // 加 + ZWSP + 微 + 信：零宽字符剥离后命中，区间覆盖被剥离位置
        List<MatchHit> hits = m.match("加\u200B微信");
        assertEquals(1, hits.size());
        assertEquals(0, hits.get(0).getStart());
        assertEquals(4, hits.get(0).getEnd(), "末字“信”在原文下标 3，区间结束为 4");
    }

    @Test
    void fullWidthAndCase_normalized() {
        DfaMatcher m = matcher("casino");
        // 全角字母 NFKC 折叠 + 大小写
        List<MatchHit> hits = m.match("ＣＡＳＩＮＯ here");
        assertEquals(1, hits.size());
        assertEquals("casino", hits.get(0).getWord());
        assertEquals(0, hits.get(0).getStart());
        assertEquals(6, hits.get(0).getEnd());
    }

    @Test
    void leetVisualConfusion_normalized() {
        DfaMatcher m = matcher("cash");
        // c4sh：4 视觉上像 a
        List<MatchHit> hits = m.match("earn c4sh now");
        assertEquals(1, hits.size());
        assertEquals(5, hits.get(0).getStart());
        assertEquals(9, hits.get(0).getEnd());
    }

    @Test
    void digitsAndFullWidthDigits_treatedAsLetters() {
        DfaMatcher m = matcher("abc123");
        // 全角数字 + 插空
        List<MatchHit> hits = m.match("x ａｂｃ-１２３ x");
        assertEquals(1, hits.size());
        assertEquals("abci2e", hits.get(0).getWord(),
                "数字 1/3 在形近归一里映射为 i/e，词条本身也走同一归一化，口径一致");
    }

    @Test
    void longestMatchWins_atSameStart() {
        DfaMatcher m = new DfaMatcher(Arrays.asList(
                new DictionaryEntry(1L, "发票", "AD", WordAction.BLOCK),
                new DictionaryEntry(2L, "代开发票", "AD", WordAction.BLOCK)));
        List<MatchHit> hits = m.match("代开发票");
        assertEquals(1, hits.size(), "同起点只取最长，不应一词多报");
        assertEquals("代开发票", hits.get(0).getWord());
    }

    @Test
    void overlappingHits_keepHigherLevel() {
        DfaMatcher m = new DfaMatcher(Arrays.asList(
                new DictionaryEntry(1L, "加微信", "AD", WordAction.MASK),
                new DictionaryEntry(2L, "微信领红包", "AD", WordAction.BLOCK)));
        // “加微信领红包”：两个命中区间重叠，拦截级优先
        List<MatchHit> hits = m.match("加微信领红包");
        assertEquals(1, hits.size());
        assertEquals("微信领红包", hits.get(0).getWord());
        assertEquals(WordAction.BLOCK, hits.get(0).getActionLevel());
    }

    @Test
    void multipleNonOverlappingHits_allReturned() {
        DfaMatcher m = matcher("赌博", "发票");
        List<MatchHit> hits = m.match("赌博和开发票");
        assertEquals(2, hits.size());
        assertEquals("赌博", hits.get(0).getWord());
        assertEquals("发票", hits.get(1).getWord());
    }

    @Test
    void hangulFillerInsertion_stillHits_andRangeCoversFillers() {
        // 韩文填充符视觉零宽（等同空格），但 JDK 8 将其归类为 Lo 字母，
        // 修复前会被当成有效字符保留而从 DFA 眼皮下溜走（APT-14 整改项）。
        DfaMatcher m = matcher("赌博");
        List<MatchHit> hits = m.match("赌ㅤ博");
        assertEquals(1, hits.size());
        assertEquals("赌博", hits.get(0).getWord());
        assertEquals(0, hits.get(0).getStart());
        assertEquals(3, hits.get(0).getEnd(), "区间需覆盖中间的 U+3164 填充符");

        // 半角填充符 U+FFA0、初成/中成填充符 U+115F/U+1160 同样拦截
        for (String filler : Arrays.asList("ﾠ", "ᅟ", "ᅠ")) {
            hits = m.match("赌" + filler + "博");
            assertEquals(1, hits.size(), "填充符 " + filler + " 不得绕过：应剥离后命中");
            assertEquals(0, hits.get(0).getStart());
            assertEquals(3, hits.get(0).getEnd());
        }

        // 多字插空：代ㅤ开ㅤ发ㅤ票，区间覆盖全部填充符
        DfaMatcher invoice = matcher("代开发票");
        hits = invoice.match("代ㅤ开ㅤ发ㅤ票");
        assertEquals(1, hits.size());
        assertEquals(0, hits.get(0).getStart());
        assertEquals(7, hits.get(0).getEnd());

        // 真实备注句式
        hits = m.match("房间没问题，下午赌ㅤ博聚会别告诉物业");
        assertEquals(1, hits.size());
        assertEquals(8, hits.get(0).getStart());
        assertEquals(11, hits.get(0).getEnd());
    }

    @Test
    void astralMathAlphabet_insertion_stillHits_andRangeCoversSurrogatePair() {
        // 星面数学粗体小写 𝐜𝐚𝐬𝐢𝐧𝐨（U+1D41C…）：逐字符 NFKC 会切断代理对导致整体丢弃，
        // 修复按码点 NFKC，折叠为 casino 后命中；区间按代理对整体计，打码不留半字符。
        DfaMatcher m = matcher("casino");
        List<MatchHit> hits = m.match("𝐜𝐚𝐬𝐢𝐧𝐨");
        assertEquals(1, hits.size());
        assertEquals("casino", hits.get(0).getWord());
        assertEquals(0, hits.get(0).getStart());
        assertEquals(12, hits.get(0).getEnd(), "6 个星面字符 = 12 个 char，区间需覆盖完整代理对");

        hits = m.match("earn 𝐜𝐚𝐬𝐢𝐧𝐨 now");
        assertEquals(1, hits.size());
        assertEquals(5, hits.get(0).getStart());
        assertEquals(17, hits.get(0).getEnd());
    }

    @Test
    void normalizeWord_stripsSeparatorsToo() {
        // 词典原文带空格/标点也会被归一，保证与文本同口径
        assertEquals("代开发票", TextNormalizer.normalizeWord("代 开 发 票"));
        assertTrue(TextNormalizer.normalizeWord("---").isEmpty());
        assertFalse(TextNormalizer.normalizeWord("发票").isEmpty());
        // 韩文填充符（含半角形态）必须剥离，不能被当成字母
        assertEquals("赌博", TextNormalizer.normalizeWord("赌ㅤ博"));
        assertEquals("赌博", TextNormalizer.normalizeWord("赌ﾠ博"));
        assertEquals("赌博", TextNormalizer.normalizeWord("赌ᅟ博"));
        assertEquals("赌博", TextNormalizer.normalizeWord("赌ᅠ博"));
        // 星面数学字母按码点 NFKC 折叠为半角，而不是被整体丢弃
        assertEquals("casino", TextNormalizer.normalizeWord("𝐜𝐚𝐬𝐢𝐧𝐨"));
        assertTrue(TextNormalizer.normalizeWord("ㅤﾠᅟᅠ").isEmpty());
    }
}
