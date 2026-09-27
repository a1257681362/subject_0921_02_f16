package com.evops.apartment.sensitive;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * 敏感词引擎注册表：词典快照来自 t_sensitive_word（仅 ACTIVE），
 * 后台每一次增改/启停后调用 {@link #rebuild()} 整体替换 DFA，即时生效、读侧无锁。
 *
 * 扫描口径：文本归一化（插空剥离、全角半角、大小写、形近替换）后跑 DFA，
 * 命中位置回映原文；命中 BLOCK(2) 级词条即判定拦截，MASK(1) 级词条按区间打码。
 */
@Component
public class SensitiveWordRegistry {
    private static final Logger log = LoggerFactory.getLogger(SensitiveWordRegistry.class);

    private final SensitiveWordMapper wordMapper;
    private final SensitiveWordProperties properties;

    private volatile Snapshot snapshot = Snapshot.empty();

    public SensitiveWordRegistry(SensitiveWordMapper wordMapper, SensitiveWordProperties properties) {
        this.wordMapper = wordMapper;
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        rebuild("启动加载");
    }

    /** 从库中重建引擎快照；任何异常都保留旧词典，不中断业务。 */
    public synchronized void rebuild(String reason) {
        try {
            List<SensitiveWord> words = wordMapper.selectList(new QueryWrapper<SensitiveWord>()
                    .eq("status", SensitiveWord.STATUS_ACTIVE)
                    .orderByAsc("id"));
            List<DictionaryEntry> entries = new ArrayList<>(words.size());
            for (SensitiveWord word : words) {
                Integer level = word.getActionLevel();
                if (level == null || !WordAction.valid(level)) {
                    continue;
                }
                String normalized = TextNormalizer.normalizeWord(word.getWordText());
                if (normalized.isEmpty()) {
                    continue;
                }
                entries.add(new DictionaryEntry(word.getId(), normalized,
                        word.getCategory(), level));
            }
            DfaMatcher matcher = new DfaMatcher(entries);
            this.snapshot = new Snapshot(matcher, entries.size());
            log.info("敏感词引擎{}：生效词条 {} 个", reason, entries.size());
        } catch (Exception e) {
            log.warn("敏感词引擎{}失败，保留当前词典: {}", reason, e.getMessage());
        }
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    /** 扫描单个字段；关闭开关或空文本直接放行。 */
    public ScanResult scan(String text) {
        if (!properties.isEnabled() || text == null || text.isEmpty()) {
            return ScanResult.clean(text);
        }
        List<MatchHit> hits = snapshot.matcher.match(text);
        if (hits.isEmpty()) {
            return ScanResult.clean(text);
        }
        boolean blocked = false;
        for (MatchHit hit : hits) {
            if (hit.getActionLevel() == WordAction.BLOCK) {
                blocked = true;
                break;
            }
        }
        return new ScanResult(blocked, hits, applyMask(text, hits));
    }

    /** 打码：MASK 级命中区间整体替换为遮蔽字符（区间覆盖插空分隔符）；BLOCK 区间不动（反正不落库）。 */
    private String applyMask(String text, List<MatchHit> hits) {
        char[] chars = null;
        char mask = maskChar();
        for (MatchHit hit : hits) {
            if (hit.getActionLevel() != WordAction.MASK) {
                continue;
            }
            if (chars == null) {
                chars = text.toCharArray();
            }
            for (int i = hit.getStart(); i < hit.getEnd() && i < chars.length; i++) {
                if (i >= 0) {
                    chars[i] = mask;
                }
            }
        }
        return chars == null ? text : new String(chars);
    }

    private char maskChar() {
        String mc = properties.getMaskChar();
        return mc == null || mc.isEmpty() ? '*' : mc.charAt(0);
    }

    /** 不可变词典快照。 */
    private static final class Snapshot {
        private final DfaMatcher matcher;
        private final int wordCount;

        private Snapshot(DfaMatcher matcher, int wordCount) {
            this.matcher = matcher;
            this.wordCount = wordCount;
        }

        private static Snapshot empty() {
            return new Snapshot(new DfaMatcher(null), 0);
        }
    }
}
