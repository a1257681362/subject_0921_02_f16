package com.evops.apartment.sensitive;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 注册表行为：总开关、分级处置（拦截/打码）、打码区间覆盖插空。
 */
class SensitiveWordRegistryTest {

    private SensitiveWordRegistry registry(boolean enabled) {
        SensitiveWordMapper mapper = mock(SensitiveWordMapper.class);
        SensitiveWord block = word(1L, "代开发票", WordAction.BLOCK);
        SensitiveWord mask = word(2L, "加微信", WordAction.MASK);
        when(mapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(
                java.util.Arrays.asList(block, mask));
        SensitiveWordProperties props = new SensitiveWordProperties();
        props.setEnabled(enabled);
        SensitiveWordRegistry registry = new SensitiveWordRegistry(mapper, props);
        registry.init();
        return registry;
    }

    private SensitiveWord word(Long id, String text, int level) {
        SensitiveWord w = new SensitiveWord();
        w.setId(id);
        w.setWordText(text);
        w.setCategory("AD");
        w.setActionLevel(level);
        w.setStatus(SensitiveWord.STATUS_ACTIVE);
        return w;
    }

    @Test
    void blockLevel_marksBlocked() {
        ScanResult result = registry(true).scan("备注：代开发票");
        assertTrue(result.isBlocked());
        assertEquals(1, result.getHits().size());
    }

    @Test
    void maskLevel_replacesRangeIncludingInsertedSeparators() {
        ScanResult result = registry(true).scan("请加-微-信联系");
        assertFalse(result.isBlocked());
        // 归一化命中“加微信”，原文区间 [1,6) 覆盖两个插空 '-'
        assertEquals("请*****联系", result.getMaskedText());
    }

    @Test
    void disabledSwitch_passesEverything() {
        ScanResult result = registry(false).scan("代开发票 加微信");
        assertFalse(result.isBlocked());
        assertTrue(result.getHits().isEmpty());
        assertEquals("代开发票 加微信", result.getMaskedText());
    }

    @Test
    void emptyAndNull_areClean() {
        SensitiveWordRegistry registry = registry(true);
        assertTrue(registry.scan(null).getHits().isEmpty());
        assertTrue(registry.scan("").getHits().isEmpty());
        assertEquals(Collections.emptyList(), registry.scan("普通文本").getHits());
    }
}
