package com.evops.apartment.pinyin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.apartment.dto.LeaseCreateRequest;
import com.evops.apartment.entity.Lease;
import com.evops.apartment.mapper.LeaseMapper;
import com.evops.apartment.service.LeaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * apt-11 拼音检索验收：样例租约播种 + 验收用例服务端执行。
 *
 * 总部巡检要求“验收时把租约样例打印出来，纸面签字确认”：
 * 打印页（{@code /api/apartment/leases/acceptance/console}）先调本服务播种样例租约
 * （多音字姓氏全覆盖：乐 le/yue、查 cha/zha、单 dan/chan/shan、解 jie/xie，另含
 * 普通姓名张学友作对照），再逐条执行验收用例（中文/全拼/首字母/多音字/大小写/全角），
 * 全部通过方可打印签字。
 *
 * 样例幂等：按合同号查重，已存在不重复建档；用例执行走与线上检索完全相同的
 * {@link LeasePinyinQuery} 条件组装，验收口径 = 线上口径。
 */
@Service
public class LeaseAcceptanceService {
    private static final Logger log = LoggerFactory.getLogger(LeaseAcceptanceService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String SAMPLE_PROPERTY = "PY-SAMPLE-001";
    private static final String SAMPLE_OPERATOR = "acceptance";

    private final LeaseMapper leaseMapper;
    private final LeaseService leaseService;
    private final LeasePinyinQuery pinyinQuery;

    public LeaseAcceptanceService(LeaseMapper leaseMapper, LeaseService leaseService,
                                  LeasePinyinQuery pinyinQuery) {
        this.leaseMapper = leaseMapper;
        this.leaseService = leaseService;
        this.pinyinQuery = pinyinQuery;
    }

    /** 样例定义：合同号 / 姓名 / 月租金。多音字姓氏全覆盖。 */
    private static final String[][] SAMPLES = {
            {"HT-PY-0001", "乐飞",   "2600.00"},
            {"HT-PY-0002", "查明",   "2800.00"},
            {"HT-PY-0003", "单丽",   "2500.00"},
            {"HT-PY-0004", "解晨",   "2700.00"},
            {"HT-PY-0005", "张学友", "3200.00"},
    };

    /** 固定样例合同号集合：验收用例只在此集合内判定，避免混入真实/测试租约。 */
    private static final List<String> SAMPLE_CONTRACTS = new ArrayList<>();
    static {
        for (String[] sample : SAMPLES) {
            SAMPLE_CONTRACTS.add(sample[0]);
        }
    }

    /** 幂等播种样例租约：逐条按合同号查重，缺失才建档（建档同事务生成拼音冗余字段）。 */
    public List<String> seedSamples() {
        List<String> created = new ArrayList<>();
        for (String[] sample : SAMPLES) {
            String contractNo = sample[0];
            Long exists = leaseMapper.selectCount(new QueryWrapper<Lease>().eq("contract_no", contractNo));
            if (exists != null && exists > 0) {
                continue;
            }
            LeaseCreateRequest req = new LeaseCreateRequest();
            req.setContractNo(contractNo);
            req.setPropertyCode(SAMPLE_PROPERTY);
            req.setTenantName(sample[1]);
            req.setTenantPhone("13800000000");
            req.setLeaseStart(LocalDate.of(2026, 9, 1));
            req.setLeaseEnd(LocalDate.of(2027, 8, 31));
            req.setMonthlyRent(new BigDecimal(sample[2]));
            req.setDeposit(new BigDecimal(sample[2]));
            req.setRemark("apt-11 拼音检索验收样例");
            req.setRequestNo("REQ-" + contractNo);
            req.setOperator(SAMPLE_OPERATOR);
            req.setBizTime(LocalDateTime.now());
            leaseService.create(req);
            created.add(contractNo);
        }
        if (!created.isEmpty()) {
            log.info("apt-11 验收样例租约播种完成 新增 {} 条: {}", created.size(), created);
        }
        return created;
    }

    /** 组装验收页数据：样例租约（含拼音冗余字段）+ 验收用例逐条执行结果。 */
    public LeaseAcceptanceData buildAcceptanceData() {
        LeaseAcceptanceData data = new LeaseAcceptanceData();
        data.setSamples(loadSamples());
        List<LeaseAcceptanceData.AcceptanceCase> cases = runCases();
        data.setCases(cases);
        int pass = 0;
        for (LeaseAcceptanceData.AcceptanceCase c : cases) {
            if (c.isPass()) {
                pass++;
            }
        }
        data.setPassCount(pass);
        data.setTotalCount(cases.size());
        data.setAllPass(pass == cases.size());
        data.setGeneratedAt(TS.format(LocalDateTime.now()));
        return data;
    }

    private List<LeaseAcceptanceData.SampleLease> loadSamples() {
        List<Lease> leases = leaseMapper.selectList(new QueryWrapper<Lease>()
                .in("contract_no", SAMPLE_CONTRACTS)
                .orderByAsc("contract_no"));
        List<LeaseAcceptanceData.SampleLease> out = new ArrayList<>();
        for (Lease lease : leases) {
            LeaseAcceptanceData.SampleLease s = new LeaseAcceptanceData.SampleLease();
            s.setContractNo(lease.getContractNo());
            s.setPropertyCode(lease.getPropertyCode());
            s.setTenantName(lease.getTenantName());
            s.setTenantNamePyFull(lease.getTenantNamePyFull());
            s.setTenantNamePyInitials(lease.getTenantNamePyInitials());
            s.setLeaseStart(String.valueOf(lease.getLeaseStart()));
            s.setLeaseEnd(String.valueOf(lease.getLeaseEnd()));
            s.setMonthlyRent(String.valueOf(lease.getMonthlyRent()));
            s.setStatus(lease.getStatus());
            out.add(s);
        }
        return out;
    }

    /**
     * 验收用例：{类别, 输入, 期望命中合同号...}。期望为空数组表示“应无命中”。
     * 覆盖：中文全名/部分、全拼连写/音节空格、首字母、多音字各读音、大小写、全角、负例。
     * 注意前缀语义：zha 同时是 查明(zha·ming) 与 张学友(zhang…) 的变体起点前缀，
     * cha 同时命中 查明(cha·ming) 与 单丽(chan·li)——多读音集合下这是预期行为。
     */
    private static final String[][] CASES = {
            {"中文全名",   "乐飞",     "HT-PY-0001"},
            {"中文部分",   "乐",       "HT-PY-0001"},
            {"全拼连写",   "lefei",    "HT-PY-0001"},
            {"多音字·另一读音", "yuefei", "HT-PY-0001"},
            {"音节空格",   "le fei",   "HT-PY-0001"},
            {"首字母",     "lf",       "HT-PY-0001"},
            {"多音字·查zha（含张zhang前缀）", "zha", "HT-PY-0002", "HT-PY-0005"},
            {"多音字·查cha（含单chan前缀）", "cha", "HT-PY-0002", "HT-PY-0003"},
            {"多音字·单shan", "shan",  "HT-PY-0003"},
            {"多音字·解xie", "xie",    "HT-PY-0004"},
            {"大小写容错", "LEFEI",    "HT-PY-0001"},
            {"全角容错",   "ｌｅｆｅｉ", "HT-PY-0001"},
            {"前缀区分",   "zham",     "HT-PY-0002"},
            {"首字母对照", "zxy",      "HT-PY-0005"},
            {"负例·无命中", "qqq"},
            {"负例·音节中间不命中", "uef"},
    };

    private List<LeaseAcceptanceData.AcceptanceCase> runCases() {
        List<LeaseAcceptanceData.AcceptanceCase> out = new ArrayList<>();
        int no = 1;
        for (String[] def : CASES) {
            String category = def[0];
            String input = def[1];
            Set<String> expected = new LinkedHashSet<>(Arrays.asList(def).subList(2, def.length));

            QueryWrapper<Lease> query = new QueryWrapper<>();
            query.in("contract_no", SAMPLE_CONTRACTS);
            pinyinQuery.applyKeyword(query, input);
            query.orderByAsc("contract_no");
            List<Lease> hits = leaseMapper.selectList(query);
            List<String> actual = new ArrayList<>();
            for (Lease lease : hits) {
                actual.add(lease.getContractNo());
            }

            LeaseAcceptanceData.AcceptanceCase c = new LeaseAcceptanceData.AcceptanceCase();
            c.setNo(no++);
            c.setCategory(category);
            c.setInput(input);
            c.setExpectedContracts(new ArrayList<>(expected));
            c.setExpectDesc(expected.isEmpty() ? "应无命中" : "命中 " + String.join("、", expected));
            c.setActualContracts(actual);
            c.setPass(new LinkedHashSet<>(actual).equals(expected));
            out.add(c);
        }
        return out;
    }
}
