package com.evops.apartment.pinyin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.apartment.entity.Lease;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 租约姓名关键字的 SQL 谓词组装（apt-11 三路匹配），检索与验收打印页共用同一份条件口径，
 * 避免“页面验收一套、线上检索一套”。
 *
 * 三路（OR）：中文原文 LIKE、全拼冗余字段变体起点 LIKE、首字母冗余字段变体起点 LIKE；
 * 关键字含汉字时只保留中文原文一路。LIKE 模式经 {0} 占位符参数化绑定，无注入面。
 */
@Component
public class LeasePinyinQuery {

    /** 追加姓名关键字条件；关键字为空/纯标点归一化后为空时不追加任何条件。 */
    public void applyKeyword(QueryWrapper<Lease> query, String rawKeyword) {
        if (!StringUtils.hasText(rawKeyword)) {
            return;
        }
        String keyword = PinyinIndexer.normalizeKeyword(rawKeyword);
        if (keyword.isEmpty()) {
            return;
        }
        query.and(w -> {
            w.like("tenant_name", keyword);
            if (PinyinIndexer.containsHan(keyword)) {
                return;
            }
            for (String pattern : PinyinIndexer.likePatterns(keyword)) {
                w.or().apply("tenant_name_py_full LIKE {0}", pattern);
                w.or().apply("tenant_name_py_initials LIKE {0}", pattern);
            }
        });
    }
}
