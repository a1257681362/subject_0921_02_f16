package com.evops.apartment.report.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.deepoove.poi.XWPFTemplate;
import com.deepoove.poi.config.Configure;
import com.deepoove.poi.config.ConfigureBuilder;
import com.deepoove.poi.plugin.table.LoopRowTableRenderPolicy;
import com.evops.apartment.entity.Lease;
import com.evops.apartment.entity.PropertyLedger;
import com.evops.apartment.mapper.LeaseMapper;
import com.evops.apartment.mapper.PropertyLedgerMapper;
import com.evops.apartment.mask.SensitiveFields;
import com.evops.apartment.report.ReportProperties;
import com.evops.apartment.report.dto.SignReportGenerateRequest;
import com.evops.apartment.report.entity.SignReportVersion;
import com.evops.apartment.report.mapper.SignReportVersionMapper;
import com.evops.apartment.security.ApartmentScopeGuard;
import com.evops.rbac.core.CurrentUser;
import com.evops.rbac.core.CurrentUserHolder;
import com.evops.rbac.core.RbacForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 签约结果正式 Word 报告（apt-8）。
 *
 * <p>口径：历史包袱不轻，本期只从新数据理起——以新台账（REGISTER 流水）按公寓楼归集房源，
 * 关联新签租约（t_lease），不回溯旧台账。租约明细做成循环表格行，租金合计由明细
 * {@code monthly_rent} 自动汇总；明细为空时合计为 0（不抛异常）。
 *
 * <p>归档：渲染后的 docx 只以 CREATE_NEW 落归档目录（{@code 归档根/小区/楼栋/}），
 * 文件名带楼栋+版本号+时间戳；同楼栋版本号 +1，历史报告不覆盖（文件与版本唯一约束双保险）。
 *
 * <p>权限：生成/查看均叠加当前身份最宽数据范围（SELF 本人 / SCOPE 授权片区 / ALL），
 * 每次放行落数据审计。
 */
@Service
public class SignReportService {
    private static final Logger log = LoggerFactory.getLogger(SignReportService.class);
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ReportProperties properties;
    private final PropertyLedgerMapper ledgerMapper;
    private final LeaseMapper leaseMapper;
    private final SignReportVersionMapper versionMapper;
    private final ApartmentScopeGuard scopeGuard;

    public SignReportService(ReportProperties properties,
                             PropertyLedgerMapper ledgerMapper,
                             LeaseMapper leaseMapper,
                             SignReportVersionMapper versionMapper,
                             ApartmentScopeGuard scopeGuard) {
        this.properties = properties;
        this.ledgerMapper = ledgerMapper;
        this.leaseMapper = leaseMapper;
        this.versionMapper = versionMapper;
        this.scopeGuard = scopeGuard;
    }

    /**
     * 生成一版报告：归集 → 渲染 → 落归档目录 → 登记版本。
     * 历史文件不覆盖（CREATE_NEW），版本号冲突时重算重试。
     */
    public SignReportVersion generate(SignReportGenerateRequest req) {
        if (req.getStartFrom() != null && req.getStartTo() != null
                && req.getStartTo().isBefore(req.getStartFrom())) {
            throw new IllegalArgumentException("到期查询区间不合法：结束日期早于开始日期");
        }
        String operator = scopeGuard.resolveOperator(req.getOperator());

        // 1. 按楼栋从新台账归集房源（REGISTER），确定小区并做范围校验
        BuildingScope scope = resolveBuildingScope(req.getBuildingName(), req.getCommunity());

        // 2. 关联新签租约（叠加数据范围），按起租日期区间过滤
        List<Lease> leases = loadLeases(scope.propertyCodes, req.getStartFrom(), req.getStartTo());

        // 3. 租金合计自动汇总：空明细为 0（reduce 给零初值，不抛空指针）
        BigDecimal rentTotal = leases.stream()
                .map(Lease::getMonthlyRent)
                .filter(r -> r != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, BigDecimal.ROUND_HALF_UP);

        // 4. poi-tl 渲染 docx（页眉公寓楼名 + 循环明细行 + 合计）
        byte[] docx = renderDocx(req.getBuildingName(), scope, leases, rentTotal,
                leases.size(), operator, req.getStartFrom(), req.getStartTo());

        // 5. 落归档目录并登记版本（CREATE_NEW + 版本号 uk，历史不覆盖；冲突重算）
        SignReportVersion version = archiveWithVersion(req, scope.community, operator,
                leases.size(), rentTotal, docx);
        log.info("签约结果报告已生成 building={} community={} version={} leases={} total={} file={}",
                req.getBuildingName(), scope.community, version.getVersionNo(),
                leases.size(), rentTotal, version.getFilePath());
        return version;
    }

    /** 版本记录列表：buildingName 可选，叠加数据范围。 */
    public List<SignReportVersion> list(String buildingName) {
        QueryWrapper<SignReportVersion> query = new QueryWrapper<>();
        query.eq(StringUtils.hasText(buildingName), "building_name", buildingName);
        applyVersionScope(query);
        query.orderByDesc("id");
        List<SignReportVersion> rows = versionMapper.selectList(query);
        scopeGuard.audit("apartment:sign-report:view", null,
                "buildingName=" + (buildingName == null ? "" : buildingName), rows.size());
        return rows;
    }

    /** 版本详情：按数据范围放行。 */
    public SignReportVersion getById(Long id) {
        SignReportVersion version = versionMapper.selectById(id);
        if (version == null) {
            throw new IllegalArgumentException("报告版本不存在: id=" + id);
        }
        requireVersionVisible(version);
        scopeGuard.audit("apartment:sign-report:view", null,
                "buildingName=" + version.getBuildingName() + ",version=" + version.getVersionNo(), null);
        return version;
    }

    /** 读取归档文件字节（下载用）：先做范围放行，文件缺失给出明确错误。 */
    public byte[] readArchiveFile(SignReportVersion version) {
        Path path = Paths.get(version.getFilePath());
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("归档文件缺失或已被移动: " + version.getFilePath());
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException("读取归档文件失败: " + version.getFilePath(), e);
        }
    }

    // ---------------- 归集与范围 ----------------

    private BuildingScope resolveBuildingScope(String buildingName, String requestedCommunity) {
        QueryWrapper<PropertyLedger> q = new QueryWrapper<PropertyLedger>()
                .eq("entry_type", PropertyLedger.TYPE_REGISTER)
                .eq("building", buildingName);
        if (StringUtils.hasText(requestedCommunity)) {
            q.eq("community", requestedCommunity);
        }
        List<PropertyLedger> rows = ledgerMapper.selectList(q);

        Map<String, PropertyLedger> firstByCode = new TreeMap<>();
        Set<String> communities = new LinkedHashSet<>();
        for (PropertyLedger row : rows) {
            firstByCode.putIfAbsent(row.getPropertyCode(), row);
            if (StringUtils.hasText(row.getCommunity())) {
                communities.add(row.getCommunity());
            }
        }

        String community;
        if (StringUtils.hasText(requestedCommunity)) {
            community = requestedCommunity.trim();
        } else if (communities.size() == 1) {
            community = communities.iterator().next();
        } else if (communities.isEmpty()) {
            CurrentUser user = CurrentUserHolder.get();
            if (user != null && !user.getScope().isAll() && !user.getScope().isSelf()) {
                // SCOPE 账号在台账查不到该楼，无法判定归属片区：要求显式传小区，避免越区出空报告
                throw new RbacForbiddenException("台账中未找到该公寓楼，请指定授权片区内的小区");
            }
            community = null; // ALL/无登录上下文：允许出一份空报告（合计为 0）
        } else {
            throw new IllegalArgumentException("公寓楼在台账中归属多个小区，请指定 community 后再生成: "
                    + communities);
        }
        if (community != null) {
            // SELF 不校验小区（与建档口径一致）；SCOPE 必须在授权片区内；ALL 放行
            scopeGuard.requireCommunityInScope(community);
        }
        return new BuildingScope(community, new ArrayList<>(firstByCode.keySet()), firstByCode);
    }

    private List<Lease> loadLeases(List<String> propertyCodes, LocalDate from, LocalDate to) {
        if (propertyCodes.isEmpty()) {
            // 台账无该楼房源：无明细可汇总。仍受范围约束（SELF/SCOPE 视为空），直接返回空表
            return new ArrayList<>();
        }
        QueryWrapper<Lease> query = new QueryWrapper<>();
        query.in("property_code", propertyCodes);
        query.ge(from != null, "lease_start", from);
        query.le(to != null, "lease_start", to);
        // 复用租约同一套数据范围：SELF 仅本人经办、SCOPE 限授权片区房源、ALL 全部
        scopeGuard.applyLeaseScope(query);
        query.orderByAsc("lease_start", "id");
        return leaseMapper.selectList(query);
    }

    private void applyVersionScope(QueryWrapper<SignReportVersion> query) {
        CurrentUser user = CurrentUserHolder.get();
        if (user == null || user.getScope().isAll()) {
            return;
        }
        if (user.getScope().isSelf()) {
            query.eq("operator", user.getUsername());
        } else if (user.getScope().getCommunities().isEmpty()) {
            query.apply("1 = 0");
        } else {
            query.in("community", new ArrayList<>(user.getScope().getCommunities()));
        }
    }

    private void requireVersionVisible(SignReportVersion version) {
        CurrentUser user = CurrentUserHolder.get();
        if (user == null || user.getScope().isAll()) {
            return;
        }
        if (user.getScope().isSelf()) {
            if (!user.getUsername().equals(version.getOperator())) {
                throw new RbacForbiddenException("仅可下载本人生成的报告");
            }
        } else if (version.getCommunity() == null
                || !user.getScope().getCommunities().contains(version.getCommunity())) {
            throw new RbacForbiddenException("报告所属小区不在授权片区范围内");
        }
    }

    // ---------------- poi-tl 渲染 ----------------

    private byte[] renderDocx(String buildingName, BuildingScope scope, List<Lease> leases,
                              BigDecimal rentTotal, int leaseCount, String operator,
                              LocalDate from, LocalDate to) {
        ConfigureBuilder builder = Configure.builder();
        // {{leases}} 为循环触发行（onSameLine：触发标签即数据行），行内字段用 [字段] 由 LoopRow 解析；
        // 空 List 时该模板行被移除，合计行保留并显示 0
        builder.bind("leases", new LoopRowTableRenderPolicy(true));
        Configure config = builder.build();

        List<Map<String, Object>> details = new ArrayList<>(leases.size());
        int index = 1;
        for (Lease lease : leases) {
            Map<String, Object> row = new LinkedHashMap<>();
            // seq 为每行序号（1 基），由序号格内 [seq] 渲染；触发标签 {{leases}} 自身渲染为空
            row.put("seq", index);
            row.put("contractNo", nullToEmpty(lease.getContractNo()));
            row.put("propertyCode", nullToEmpty(lease.getPropertyCode()));
            PropertyLedger ledger = scope.ledgerByCode.get(lease.getPropertyCode());
            row.put("roomNo", ledger == null ? "" : nullToEmpty(ledger.getRoomNo()));
            // 租客姓名与租约检索/CSV 导出共用同一套脱敏规则（apt-2）
            row.put("tenantName", SensitiveFields.readAsString(lease, "tenantName"));
            row.put("leaseStart", lease.getLeaseStart() == null ? "" : DATE.format(lease.getLeaseStart()));
            row.put("leaseEnd", lease.getLeaseEnd() == null ? "" : DATE.format(lease.getLeaseEnd()));
            row.put("monthlyRent", money(lease.getMonthlyRent()));
            row.put("deposit", money(lease.getDeposit()));
            details.add(row);
            index++;
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("buildingName", buildingName);
        root.put("community", scope.community == null ? "（未指定小区）" : scope.community);
        root.put("period", periodText(from, to));
        root.put("generatedAt", LocalDateTime.now().format(DT));
        root.put("operator", operator);
        root.put("leaseCount", leaseCount);
        root.put("totalRent", rentTotal.toPlainString());
        root.put("leases", details);

        try (InputStream tpl = new ClassPathResource(properties.getTemplateClasspath()).getInputStream();
             XWPFTemplate template = XWPFTemplate.compile(tpl, config);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            template.render(root);
            template.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("读取报告模板失败: " + properties.getTemplateClasspath(), e);
        } catch (Exception e) {
            throw new IllegalStateException("渲染签约结果 Word 报告失败: " + e.getMessage(), e);
        }
    }

    // ---------------- 归档与版本 ----------------

    private SignReportVersion archiveWithVersion(SignReportGenerateRequest req, String community,
                                                 String operator, int leaseCount,
                                                 BigDecimal rentTotal, byte[] docx) {
        Path archiveRoot = Paths.get(properties.getArchiveDir());
        String safeBuilding = sanitize(req.getBuildingName());
        String safeCommunity = community == null ? "_未分区" : sanitize(community);
        Path dir = archiveRoot.resolve(safeCommunity).resolve(safeBuilding);

        // 版本号与文件名在并发下可能撞车：CREATE_NEW / uk 任一冲突都重算，最多重试若干次
        RuntimeException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            int versionNo = nextVersionNo(req.getBuildingName());
            String ts = LocalDateTime.now().format(FILE_TS);
            String fileName = "签约结果报告_" + safeBuilding + "_v" + versionNo + "_" + ts + ".docx";
            Path target = dir.resolve(fileName);
            try {
                Files.createDirectories(dir);
                // 只新建不覆盖：历史报告即使版本号/时间戳碰撞也绝不被改写
                Files.createFile(target);
                Files.write(target, docx);
            } catch (FileAlreadyExistsException e) {
                last = new IllegalStateException("归档文件已存在，换名重试: " + target, e);
                continue;
            } catch (IOException e) {
                throw new IllegalStateException("写入归档文件失败: " + target, e);
            }

            SignReportVersion version = new SignReportVersion();
            version.setReportType(SignReportVersion.TYPE_SIGN_RESULT);
            version.setBuildingName(req.getBuildingName());
            version.setCommunity(community);
            version.setVersionNo(versionNo);
            version.setFileName(fileName);
            version.setFilePath(target.toAbsolutePath().normalize().toString());
            version.setLeaseCount(leaseCount);
            version.setRentTotal(rentTotal);
            version.setLeaseStartFrom(req.getStartFrom());
            version.setLeaseStartTo(req.getStartTo());
            version.setRequestNo(req.getRequestNo());
            version.setOperator(operator);
            version.setBizTime(LocalDateTime.now());
            version.setDataVersion(1);
            try {
                versionMapper.insert(version);
            } catch (RuntimeException dup) {
                // 并发同楼栋版本号撞 uk：删掉刚落盘的候选文件，重算版本号/时间戳再来
                deleteQuietly(target);
                last = dup;
                continue;
            }
            scopeGuard.audit("apartment:sign-report:generate", req.getRequestNo(),
                    "buildingName=" + req.getBuildingName() + ",community=" + community
                            + ",version=" + versionNo + ",leaseCount=" + leaseCount, leaseCount);
            return version;
        }
        throw new IllegalStateException("报告归档多次冲突，放弃本次生成: " + last.getMessage(), last);
    }

    private int nextVersionNo(String buildingName) {
        SignReportVersion latest = versionMapper.selectOne(new QueryWrapper<SignReportVersion>()
                .eq("building_name", buildingName)
                .orderByDesc("version_no").last("LIMIT 1"));
        return latest == null || latest.getVersionNo() == null ? 1 : latest.getVersionNo() + 1;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 候选孤儿文件留给运维清理，不影响主流程
        }
    }

    // ---------------- 工具 ----------------

    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String periodText(LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return "不限";
        }
        String f = from == null ? "不限" : DATE.format(from);
        String t = to == null ? "不限" : DATE.format(to);
        return f + " 至 " + t;
    }

    /** 文件名安全化：去路径分隔与 Windows 非法字符，空白收敛为下划线。 */
    private static String sanitize(String raw) {
        String value = raw.trim().replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", "_");
        return value.isEmpty() ? "_" : value;
    }

    /** 楼栋归集中间结果。 */
    private static class BuildingScope {
        final String community;
        final List<String> propertyCodes;
        final Map<String, PropertyLedger> ledgerByCode;

        BuildingScope(String community, List<String> propertyCodes,
                      Map<String, PropertyLedger> ledgerByCode) {
            this.community = community;
            this.propertyCodes = propertyCodes;
            this.ledgerByCode = ledgerByCode;
        }
    }
}
