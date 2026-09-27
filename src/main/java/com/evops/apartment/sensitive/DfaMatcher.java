package com.evops.apartment.sensitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Trie 的确定有限自动机（DFA）敏感词匹配。
 *
 * 匹配过程：文本先经 {@link TextNormalizer} 归一化，随后从每个位置沿 Trie 转移，
 * 走到终止节点即为一次命中；不做失败指针回退（中文词库与“逐起点扫描”足够，语义也最直观）。
 * 同一位置若多个词条同时终止，取最长词条；产出的命中按起点排序、重叠区间去重（保留最长/级别最高）。
 *
 * 线程模型：词典快照不可变，{@link SensitiveWordRegistry} 维护词典时整体替换本对象，
 * 匹配侧只读快照、无需加锁。
 */
public final class DfaMatcher {
    private final Node root = new Node();

    public DfaMatcher(List<DictionaryEntry> entries) {
        if (entries != null) {
            for (DictionaryEntry entry : entries) {
                add(entry);
            }
        }
    }

    private void add(DictionaryEntry entry) {
        String word = entry.getNormalizedWord();
        if (word == null || word.isEmpty()) {
            return;
        }
        Node node = root;
        for (int i = 0; i < word.length(); i++) {
            node = node.children.computeIfAbsent(word.charAt(i), k -> new Node());
        }
        // 归一化后同形词条只保留一条：级别更高（BLOCK）的覆盖
        if (node.terminal == null || entry.getActionLevel() > node.terminal.getActionLevel()) {
            node.terminal = entry;
        }
    }

    /**
     * 扫描文本，返回去重后的全部命中（含原文位置）。
     */
    public List<MatchHit> match(String rawText) {
        Normalized norm = TextNormalizer.normalize(rawText);
        String text = norm.getText();
        int len = text.length();
        if (len == 0) {
            return Collections.emptyList();
        }
        List<MatchHit> raw = new ArrayList<>();
        for (int i = 0; i < len; i++) {
            Node node = root;
            int end = i;
            DictionaryEntry terminal = null;
            for (int j = i; j < len; j++) {
                Node next = node.children.get(text.charAt(j));
                if (next == null) {
                    break;
                }
                node = next;
                if (node.terminal != null) {
                    terminal = node.terminal;
                    end = j + 1;
                }
            }
            if (terminal != null) {
                int originStart = norm.originIndex(i);
                // 归一化末字在原文中的位置 + 该码点的 char 宽度：
                // BMP 为 +1；星面字符是代理对需 +2，否则打码只盖高代理位、留下孤立的低代理位。
                // 区间天然覆盖首尾之间所有被剥离的插空字符。
                int lastOrigin = norm.originIndex(end - 1);
                int originEnd = lastOrigin + Character.charCount(rawText.codePointAt(lastOrigin));
                raw.add(new MatchHit(terminal.getNormalizedWord(), terminal.getCategory(),
                        terminal.getActionLevel(), originStart, originEnd));
            }
        }
        return dedupOverlaps(raw);
    }

    /**
     * 重叠区间去重：起点相同时只留最长；新命中与已选命中区间重叠时，
     * 保留处置级别更高者（同为拦截时保留更长者），避免一词多报、打码区间打架。
     */
    private List<MatchHit> dedupOverlaps(List<MatchHit> hits) {
        List<MatchHit> sorted = new ArrayList<>(hits);
        sorted.sort(Comparator.comparingInt(MatchHit::getStart)
                .thenComparing(Comparator.comparingInt(MatchHit::getEnd).reversed()));
        List<MatchHit> picked = new ArrayList<>();
        for (MatchHit hit : sorted) {
            boolean covered = false;
            for (int k = 0; k < picked.size(); k++) {
                MatchHit prev = picked.get(k);
                if (hit.getStart() >= prev.getEnd()) {
                    continue; // 不重叠
                }
                covered = true;
                // 重叠：拦截级优先；同级别更长者优先
                if (hit.getActionLevel() > prev.getActionLevel()
                        || (hit.getActionLevel() == prev.getActionLevel()
                        && hit.getEnd() - hit.getStart() > prev.getEnd() - prev.getStart())) {
                    picked.set(k, hit);
                }
                break;
            }
            if (!covered) {
                picked.add(hit);
            }
        }
        return picked;
    }

    /** Trie 节点。 */
    private static final class Node {
        private final Map<Character, Node> children = new HashMap<>();
        private DictionaryEntry terminal;
    }
}
