package com.evops.apartment.pinyin;

import com.evops.apartment.mask.SensitiveFields;
import com.evops.apartment.mask.SensitiveType;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 租客姓名拼音索引生成（apt-11 总部巡检：列表检索支持拼音）。
 *
 * 口径：
 * 1. 每个汉字取其<strong>全部</strong>多音字读音（无声调、小写、去重），逐字读音做笛卡尔积，
 *    故「乐飞」同时收录 lefei/yuefei，「重庆」同时收录 zhongqing/chongqing，
 *    检索任一读法都能命中，不依赖姓氏词库猜测；
 * 2. 非汉字（字母/数字）按原样透传，标点与空格不作为读音；
 * 3. 每个读音组合产出两种写法：连写（zhangxueyou）与音节空格（zhang xue you），
 *    首字母同理（zxy / z x y），分别存入全拼、首字母两个冗余字段；
 * 4. 字段以 {@link #VARIANT_DELIMITER} 包裹分隔每个变体，检索时按变体起点做前缀匹配，
 *    不会在音节中间误命中；
 * 5. 组合数/字段长度有上限（{@link #MAX_VARIANTS}、{@link #MAX_FIELD_LENGTH}），
 *    超限按生成顺序截断并告警——常见姓名（约 6 字内、每字至多 3 个无声调读音）远达不到上限。
 *
 * 大小写/全半角容错与 {@link #normalizeKeyword(String)} 同口径：逐字 NFKC 折叠（Ａ→A、
 * 全角空格→半角空格）后小写，索引生成与检索词处理走同一套规则。
 */
public final class PinyinIndexer {
    private static final Logger log = LoggerFactory.getLogger(PinyinIndexer.class);

    /** 变体分隔符：字段形如 |variant1|variant2|，检索以 %|前缀% 做变体起点前缀匹配。 */
    public static final String VARIANT_DELIMITER = "|";
    /** 最多收录的读音组合数（全拼/首字母各自计数前的组合上限）。 */
    public static final int MAX_VARIANTS = 128;
    /** 冗余字段落库长度上限（VARCHAR 2048），留足余量。 */
    public static final int MAX_FIELD_LENGTH = 2000;

    private static final HanyuPinyinOutputFormat FORMAT = new HanyuPinyinOutputFormat();

    static {
        FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
    }

    private PinyinIndexer() {
    }

    /**
     * 生成姓名的全拼/首字母冗余字段值。姓名为空或无任何有效字符时两个字段均为 null。
     */
    public static PinyinIndex build(String name) {
        List<String> syllables = flattenSyllables(name);
        if (syllables.isEmpty()) {
            return PinyinIndex.EMPTY;
        }
        Set<String> fullVariants = new LinkedHashSet<>();
        Set<String> initialVariants = new LinkedHashSet<>();
        boolean truncated = false;
        try {
            combine(syllables, 0, new ArrayList<String>(), fullVariants, initialVariants);
        } catch (VariantLimitException e) {
            truncated = true;
        }
        if (truncated) {
            // apt-12：日志输出与接口/导出同一套脱敏规则，姓名不明文入日志
            log.warn("姓名拼音组合数超限已截断 name={} 收录全拼变体={} 首字母变体={}（上限 {}）",
                    SensitiveFields.maskValue(SensitiveType.NAME, name),
                    fullVariants.size(), initialVariants.size(), MAX_VARIANTS);
        }
        String full = joinVariants(fullVariants, name);
        String initials = joinVariants(initialVariants, name);
        if (full == null || initials == null) {
            // 字段长度超限时只剩部分变体；极端情况下全部装不下，回退为首字母兜底不应发生（此处防御）
            log.warn("姓名拼音字段超长未能完整落库 name={}",
                    SensitiveFields.maskValue(SensitiveType.NAME, name));
        }
        return new PinyinIndex(full, initials);
    }

    /**
     * 检索词归一化（与索引端同口径）：逐字 NFKC（全角→半角）→ 小写 → 折叠空白。
     * 全角字母「ＺＨＡＮＧ」、大写「Zhang」、全角空格均在此收敛。
     */
    public static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(keyword.length());
        for (int i = 0; i < keyword.length(); i++) {
            String folded = Normalizer.normalize(String.valueOf(keyword.charAt(i)), Normalizer.Form.NFKC);
            sb.append(folded.toLowerCase());
        }
        return sb.toString().trim().replaceAll("\\s+", " ");
    }

    /** 检索词是否含汉字：含汉字时走中文原文匹配，纯字母/数字走拼音字段匹配。 */
    public static boolean containsHan(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (isHan(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 拼音字段的 LIKE 模式（变体起点前缀匹配，大小写已在字段端与检索词端收敛）：
     * 连写形式（zhangxue）与音节空格形式（zhang xue）各一条；不含空模式。
     */
    public static List<String> likePatterns(String normalizedKeyword) {
        List<String> patterns = new ArrayList<>(2);
        if (normalizedKeyword == null || normalizedKeyword.isEmpty()) {
            return patterns;
        }
        String continuous = normalizedKeyword.replace(" ", "");
        if (!continuous.isEmpty()) {
            patterns.add("%" + VARIANT_DELIMITER + continuous + "%");
        }
        if (normalizedKeyword.indexOf(' ') >= 0) {
            patterns.add("%" + VARIANT_DELIMITER + normalizedKeyword + "%");
        }
        return patterns;
    }

    /**
     * 把逐字符的读音集合压平为“音节”序列：每个汉字一个元素（多读音仍在元素内保留），
     * 字母/数字逐字符透传。读音集合为空的汉字（生僻字 pinyin4j 未收录）以原字透传，
     * 保证中文原文匹配不受影响、拼音段不丢字位。
     */
    private static List<String> flattenSyllables(String name) {
        List<String> syllables = new ArrayList<>();
        if (name == null) {
            return syllables;
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (isInvisible(ch)) {
                continue;
            }
            // 与检索端同口径逐字 NFKC（全角外文 Ｔ→t），保证全角外文名也能按半角检索
            String folded = Normalizer.normalize(String.valueOf(ch), Normalizer.Form.NFKC);
            for (int k = 0; k < folded.length(); k++) {
                char fc = folded.charAt(k);
                if (isInvisible(fc)) {
                    continue;
                }
                appendChar(syllables, fc);
            }
        }
        return syllables;
    }

    private static void appendChar(List<String> syllables, char ch) {
        if (isHan(ch)) {
            List<String> readings = hanReadings(ch);
            if (readings.isEmpty()) {
                syllables.add(String.valueOf(ch));
            } else {
                // 一个汉字的多个读音编码在一个音节元素内，逗号分隔，组合时展开
                syllables.add(String.join(",", readings));
            }
        } else if (Character.isLetterOrDigit(ch)) {
            syllables.add(String.valueOf(Character.toLowerCase(ch)));
        }
        // 标点/空格跳过：不作为读音，也不占音节位
    }

    private static boolean isInvisible(char ch) {
        // 与 apt-4 TextNormalizer 对齐：BOM/零宽字符在姓名中无意义，先剥离避免占音节位
        if (ch == '\u200B' || ch == '\u200C' || ch == '\u200D' || ch == '\u2060'
                || ch == '\uFEFF' || ch == '\u00AD' || ch == '\u180E') {
            return true;
        }
        return ch >= '\uFE00' && ch <= '\uFE0F';
    }

    private static List<String> hanReadings(char ch) {
        List<String> readings = new ArrayList<>();
        try {
            String[] candidates = PinyinHelper.toHanyuPinyinStringArray(ch, FORMAT);
            if (candidates != null) {
                for (String candidate : candidates) {
                    if (!readings.contains(candidate)) {
                        readings.add(candidate);
                    }
                }
            }
        } catch (BadHanyuPinyinOutputFormatCombination e) {
            // 格式为常量配置，理论上不会发生
            log.warn("拼音转换异常 ch={}", ch, e);
        }
        return readings;
    }

    /**
     * 逐字读音笛卡尔积。每个叶子产出全拼连写/音节空格与首字母连写/空格四种写法。
     * 组合数达 {@link #MAX_VARIANTS} 抛异常中止展开（调用方据此告警）。
     */
    private static void combine(List<String> syllables, int index, List<String> picked,
                                Set<String> fullVariants, Set<String> initialVariants)
            throws VariantLimitException {
        if (index == syllables.size()) {
            // 每个叶子组合向全拼集合写入两个变体（连写/空格），故组合数 = size/2
            if (fullVariants.size() / 2 >= MAX_VARIANTS) {
                throw new VariantLimitException();
            }
            fullVariants.add(String.join("", picked));
            fullVariants.add(String.join(" ", picked));
            StringBuilder initials = new StringBuilder();
            StringBuilder initialsSpaced = new StringBuilder();
            for (String syllable : picked) {
                if (!syllable.isEmpty()) {
                    char c = syllable.charAt(0);
                    initials.append(c);
                    if (initialsSpaced.length() > 0) {
                        initialsSpaced.append(' ');
                    }
                    initialsSpaced.append(c);
                }
            }
            initialVariants.add(initials.toString());
            initialVariants.add(initialsSpaced.toString());
            return;
        }
        for (String reading : syllables.get(index).split(",")) {
            picked.add(reading);
            combine(syllables, index + 1, picked, fullVariants, initialVariants);
            picked.remove(picked.size() - 1);
        }
    }

    /** 变体集合拼成落库字段：|v1|v2|；按长度上限截断，全部装不下返回 null。 */
    private static String joinVariants(Set<String> variants, String name) {
        StringBuilder sb = new StringBuilder(VARIANT_DELIMITER);
        for (String variant : variants) {
            String token = variant + VARIANT_DELIMITER;
            if (sb.length() + token.length() > MAX_FIELD_LENGTH) {
                log.warn("姓名拼音字段长度达上限截断 name={} 已收录变体长度={}",
                        SensitiveFields.maskValue(SensitiveType.NAME, name), sb.length());
                break;
            }
            sb.append(token);
        }
        return sb.length() <= 1 ? null : sb.toString();
    }

    private static boolean isHan(char ch) {
        return ch >= '一' && ch <= '鿿';
    }

    private static final class VariantLimitException extends Exception {
    }
}
