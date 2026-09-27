package com.evops.apartment.sensitive;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.rbac.core.CurrentUser;
import com.evops.rbac.core.CurrentUserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 敏感词词典后台维护：增改、启停均留操作日志并即时重建 DFA。
 * 操作人取登录账号（无登录上下文时记 system，仅任务/单测场景）。
 */
@Service
public class SensitiveWordService {
    private static final List<String> CATEGORIES =
            Arrays.asList(SensitiveWord.CATEGORY_AD, SensitiveWord.CATEGORY_ILLEGAL,
                    SensitiveWord.CATEGORY_OTHER);

    private final SensitiveWordMapper wordMapper;
    private final SensitiveWordOpLogMapper opLogMapper;
    private final SensitiveWordRegistry registry;

    public SensitiveWordService(SensitiveWordMapper wordMapper,
                                SensitiveWordOpLogMapper opLogMapper,
                                SensitiveWordRegistry registry) {
        this.wordMapper = wordMapper;
        this.opLogMapper = opLogMapper;
        this.registry = registry;
    }

    public List<SensitiveWord> list(String status, String category, String keyword) {
        QueryWrapper<SensitiveWord> query = new QueryWrapper<>();
        query.eq(StringUtils.hasText(status), "status", status);
        query.eq(StringUtils.hasText(category), "category", category);
        query.like(StringUtils.hasText(keyword), "word_text", keyword);
        query.orderByDesc("id");
        return wordMapper.selectList(query);
    }

    @Transactional(rollbackFor = Exception.class)
    public SensitiveWord create(SensitiveWordUpsertRequest req) {
        validate(req);
        String normalized = TextNormalizer.normalizeWord(req.getWordText());
        requireNormalizedFree(normalized, null);

        SensitiveWord word = new SensitiveWord();
        word.setWordText(req.getWordText().trim());
        word.setCategory(req.getCategory());
        word.setActionLevel(req.getActionLevel());
        word.setStatus(SensitiveWord.STATUS_ACTIVE);
        word.setCreatedBy(currentOperator());
        word.setRemark(req.getRemark());
        word.setCreatedAt(LocalDateTime.now());
        word.setUpdatedAt(LocalDateTime.now());
        wordMapper.insert(word);

        log(word, SensitiveWordOpLog.ACTION_CREATE, null, snapshot(word));
        registry.rebuild("新增词条[" + word.getWordText() + "]");
        return word;
    }

    @Transactional(rollbackFor = Exception.class)
    public SensitiveWord update(Long id, SensitiveWordUpsertRequest req) {
        SensitiveWord word = wordMapper.selectById(id);
        if (word == null) {
            throw new IllegalArgumentException("敏感词不存在: id=" + id);
        }
        validate(req);
        String before = snapshot(word);
        String normalized = TextNormalizer.normalizeWord(req.getWordText());
        requireNormalizedFree(normalized, id);

        word.setWordText(req.getWordText().trim());
        word.setCategory(req.getCategory());
        word.setActionLevel(req.getActionLevel());
        word.setRemark(req.getRemark());
        word.setUpdatedBy(currentOperator());
        word.setUpdatedAt(LocalDateTime.now());
        wordMapper.updateById(word);

        log(word, SensitiveWordOpLog.ACTION_UPDATE, before, snapshot(word));
        registry.rebuild("修改词条[" + word.getWordText() + "]");
        return word;
    }

    @Transactional(rollbackFor = Exception.class)
    public SensitiveWord setStatus(Long id, boolean active) {
        SensitiveWord word = wordMapper.selectById(id);
        if (word == null) {
            throw new IllegalArgumentException("敏感词不存在: id=" + id);
        }
        String target = active ? SensitiveWord.STATUS_ACTIVE : SensitiveWord.STATUS_DISABLED;
        if (target.equals(word.getStatus())) {
            return word;
        }
        String before = snapshot(word);
        word.setStatus(target);
        word.setUpdatedBy(currentOperator());
        word.setUpdatedAt(LocalDateTime.now());
        wordMapper.updateById(word);

        log(word, active ? SensitiveWordOpLog.ACTION_ENABLE : SensitiveWordOpLog.ACTION_DISABLE,
                before, snapshot(word));
        registry.rebuild((active ? "启用" : "停用") + "词条[" + word.getWordText() + "]");
        return word;
    }

    /** 维护留痕查询：可按词条过滤，默认最近 200 条。 */
    public List<SensitiveWordOpLog> listOpLogs(Long wordId) {
        QueryWrapper<SensitiveWordOpLog> query = new QueryWrapper<>();
        query.eq(wordId != null, "word_id", wordId);
        query.orderByDesc("id").last("LIMIT 200");
        return opLogMapper.selectList(query);
    }

    private void validate(SensitiveWordUpsertRequest req) {
        if (!StringUtils.hasText(req.getWordText())) {
            throw new IllegalArgumentException("敏感词内容不能为空");
        }
        if (!CATEGORIES.contains(req.getCategory())) {
            throw new IllegalArgumentException("词类非法，仅支持 AD/ILLEGAL/OTHER");
        }
        if (req.getActionLevel() == null || !WordAction.valid(req.getActionLevel())) {
            throw new IllegalArgumentException("处置级别非法：1=打码，2=拦截");
        }
        if (TextNormalizer.normalizeWord(req.getWordText()).isEmpty()) {
            throw new IllegalArgumentException("敏感词归一化后为空（至少含一个字母/数字/汉字）");
        }
    }

    /** 归一化同形词条拦截：避免两个原文不同的词在引擎里静默互相覆盖。 */
    private void requireNormalizedFree(String normalized, Long selfId) {
        List<SensitiveWord> all = wordMapper.selectList(new QueryWrapper<SensitiveWord>()
                .eq("status", SensitiveWord.STATUS_ACTIVE));
        for (SensitiveWord other : all) {
            if (selfId != null && selfId.equals(other.getId())) {
                continue;
            }
            if (normalized.equals(TextNormalizer.normalizeWord(other.getWordText()))) {
                throw new IllegalArgumentException(
                        "与已有词条「" + other.getWordText() + "」归一化后相同，命中会冲突，请直接维护原词条");
            }
        }
    }

    private void log(SensitiveWord word, String action, String beforeJson, String afterJson) {
        SensitiveWordOpLog opLog = new SensitiveWordOpLog();
        opLog.setWordId(word.getId());
        opLog.setWordText(word.getWordText());
        opLog.setAction(action);
        opLog.setBeforeJson(beforeJson);
        opLog.setAfterJson(afterJson);
        opLog.setOperator(currentOperator());
        opLog.setOpTime(LocalDateTime.now());
        opLogMapper.insert(opLog);
    }

    /** 词条快照（简易 JSON，字段固定可控，自行转义避免再引依赖）。 */
    private String snapshot(SensitiveWord w) {
        return "{\"id\":" + w.getId()
                + ",\"wordText\":\"" + jsonEscape(w.getWordText()) + "\""
                + ",\"category\":\"" + jsonEscape(w.getCategory()) + "\""
                + ",\"actionLevel\":" + w.getActionLevel()
                + ",\"status\":\"" + jsonEscape(w.getStatus()) + "\""
                + ",\"remark\":\"" + jsonEscape(w.getRemark()) + "\"}";
    }

    private String jsonEscape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", " ").replace("\n", " ");
    }

    private String currentOperator() {
        CurrentUser user = CurrentUserHolder.get();
        return user == null ? "system" : user.getUsername();
    }
}
