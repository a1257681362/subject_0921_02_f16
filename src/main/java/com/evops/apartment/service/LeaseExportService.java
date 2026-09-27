package com.evops.apartment.service;

import com.evops.apartment.entity.Lease;
import com.evops.apartment.mask.SensitiveFields;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * 租约导出：检索条件与 {@link LeaseService#search} 完全一致。
 * 姓名、手机号、证件号不做任何特判，统一经字段上的 @Sensitive 注解读取，
 * 与接口返回共用同一套规则（{@link com.evops.apartment.mask.SensitiveRuleRegistry}）。
 */
@Service
public class LeaseExportService {
    /** UTF-8 BOM，Excel 打开 CSV 时据此识别编码，避免中文乱码。 */
    private static final char UTF8_BOM = 0xFEFF;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String[] HEADERS = {
            "合同号", "房源编号", "租客姓名", "手机号", "证件号",
            "起租日期", "到期日期", "月租金", "押金", "状态", "更新时间", "紧急联系人"
    };
    /** 导出字段名；与 HEADERS 一一对应，带 @Sensitive 的字段（含嵌套对象内部）自动脱敏。 */
    private static final String[] FIELDS = {
            "contractNo", "propertyCode", "tenantName", "tenantPhone", "tenantIdCard",
            "leaseStart", "leaseEnd", "monthlyRent", "deposit", "status", "updatedAt", "emergencyContact"
    };

    private final LeaseService leaseService;

    public LeaseExportService(LeaseService leaseService) {
        this.leaseService = leaseService;
    }

    /** 生成 UTF-8（带 BOM）CSV 文本，CRLF 行分隔。 */
    public String exportLeasesCsv(String contractNo, LocalDate startFrom, LocalDate startTo, String keyword) {
        List<Lease> leases = leaseService.search(contractNo, startFrom, startTo, keyword,
                "apartment:lease:export");
        StringBuilder sb = new StringBuilder();
        sb.append(UTF8_BOM);
        appendRow(sb, Arrays.asList(HEADERS));
        for (Lease lease : leases) {
            String[] cells = new String[FIELDS.length];
            for (int i = 0; i < FIELDS.length; i++) {
                cells[i] = render(lease, FIELDS[i]);
            }
            appendRow(sb, Arrays.asList(cells));
        }
        return sb.toString();
    }

    private String render(Lease lease, String field) {
        if ("updatedAt".equals(field) && lease.getUpdatedAt() != null) {
            return DATE_TIME.format(lease.getUpdatedAt());
        }
        // 敏感字段与普通字段走同一反射入口；@Sensitive 决定是否脱敏、按哪类规则脱敏
        return SensitiveFields.readAsString(lease, field);
    }

    private void appendRow(StringBuilder sb, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(cells.get(i)));
        }
        sb.append("\r\n");
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
