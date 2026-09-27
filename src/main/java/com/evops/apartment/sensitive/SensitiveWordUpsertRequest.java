package com.evops.apartment.sensitive;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 敏感词新增/修改请求。
 */
public class SensitiveWordUpsertRequest {
    /** 修改时为空；新增必填。 */
    private Long id;
    @NotBlank(message = "敏感词内容不能为空")
    private String wordText;
    @NotBlank(message = "词类不能为空")
    private String category;
    @NotNull(message = "处置级别不能为空")
    private Integer actionLevel;
    private String remark;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getWordText() { return wordText; }
    public void setWordText(String wordText) { this.wordText = wordText; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Integer getActionLevel() { return actionLevel; }
    public void setActionLevel(Integer actionLevel) { this.actionLevel = actionLevel; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
