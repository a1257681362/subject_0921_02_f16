package com.evops.apartment.entity;

import com.evops.apartment.mask.Sensitive;
import com.evops.apartment.mask.SensitiveType;

/**
 * 紧急联系人（apt-12）：租约的嵌套对象，库内以 JSON 存于 t_lease.emergency_contact。
 *
 * <p>敏感成员（姓名/电话）与租约其他字段一样打 {@link Sensitive} 注解：接口返回由
 * Jackson 递归序列化自动脱敏；租约导出与日志出口由
 * {@link com.evops.apartment.mask.SensitiveFields} 递归遍历同一注解脱敏——
 * 嵌套层级与扁平字段共用 {@link com.evops.apartment.mask.SensitiveRuleRegistry} 同一套规则。
 */
public class EmergencyContact {
    @Sensitive(SensitiveType.NAME)
    private String name;
    @Sensitive(SensitiveType.PHONE)
    private String phone;
    /** 与租客关系（如 配偶/父母/同事），非敏感，原样输出。 */
    private String relation;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getRelation() { return relation; }
    public void setRelation(String relation) { this.relation = relation; }

    /**
     * 排查用途的字段直拼。注意：出口（导出/日志）不得依赖本方法渲染——
     * 明文会随之带出；一律走 {@link com.evops.apartment.mask.SensitiveFields} 递归脱敏。
     */
    @Override
    public String toString() {
        return "EmergencyContact(name=" + name + ", phone=" + phone + ", relation=" + relation + ")";
    }
}
