package com.evops.apartment;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.apartment.dto.CheckoutRequest;
import com.evops.apartment.dto.DeductionItemRequest;
import com.evops.apartment.dto.LeaseCreateRequest;
import com.evops.apartment.dto.PropertyEntryCreateRequest;
import com.evops.apartment.dto.PropertyReverseRequest;
import com.evops.apartment.entity.Lease;
import com.evops.apartment.entity.LeaseCheckout;
import com.evops.apartment.entity.PropertyLedger;
import com.evops.apartment.mapper.DepositDeductionMapper;
import com.evops.apartment.mapper.LeaseCheckoutMapper;
import com.evops.apartment.mapper.LeaseMapper;
import com.evops.apartment.service.CheckoutService;
import com.evops.apartment.service.LeaseService;
import com.evops.apartment.service.PropertyLedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:evops_test;DB_CLOSE_DELAY=-1")
class ApartmentFlowTest {
    @Autowired
    private LeaseService leaseService;
    @Autowired
    private CheckoutService checkoutService;
    @Autowired
    private PropertyLedgerService propertyLedgerService;
    @Autowired
    private LeaseMapper leaseMapper;
    @Autowired
    private LeaseCheckoutMapper checkoutMapper;
    @Autowired
    private DepositDeductionMapper deductionMapper;

    @Test
    void concurrentCreateSameContractNo_onlyOneSucceeds() throws Exception {
        String contractNo = "HT-CONC-0001";
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch fire = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        Runnable task = () -> {
            ready.countDown();
            try {
                fire.await(5, TimeUnit.SECONDS);
                leaseService.create(buildLeaseReq(contractNo));
                success.incrementAndGet();
            } catch (DuplicateKeyException e) {
                conflict.incrementAndGet();
            } catch (Throwable t) {
                unexpected.add(t);
            }
        };
        Thread t1 = new Thread(task);
        Thread t2 = new Thread(task);
        t1.start();
        t2.start();
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        fire.countDown();
        t1.join(10000);
        t2.join(10000);

        assertTrue(unexpected.isEmpty(), "出现非预期异常: " + unexpected);
        assertEquals(1, success.get(), "同合同号并发建档只能成功一条");
        assertEquals(1, conflict.get(), "另一笔应命中唯一约束");
        Long count = leaseMapper.selectCount(new QueryWrapper<Lease>().eq("contract_no", contractNo));
        assertEquals(1L, count);
    }

    @Test
    void checkout_writesCheckoutDeductionsAndClosesLease() {
        Lease lease = leaseService.create(buildLeaseReq("HT-FLOW-0001"));

        CheckoutRequest req = new CheckoutRequest();
        req.setContractNo("HT-FLOW-0001");
        req.setCheckoutDate(LocalDate.of(2026, 9, 20));
        req.setInspectionResult(LeaseCheckout.RESULT_DAMAGE);
        req.setInspectionRemark("墙面污渍、水龙头损坏");
        req.setDeductions(Arrays.asList(
                deduction("保洁费", "100.00", "全屋保洁"),
                deduction("维修费", "200.00", "更换水龙头")));
        req.setRequestNo("REQ-CK-0001");
        req.setOperator("op-zhang");

        LeaseCheckout checkout = checkoutService.checkout(req);
        assertNotNull(checkout.getId());
        assertEquals(0, new BigDecimal("300.00").compareTo(checkout.getDeductTotal()));
        assertEquals(0, new BigDecimal("700.00").compareTo(checkout.getRefundAmount()));
        assertEquals("REQ-CK-0001", checkout.getRequestNo());
        assertEquals("op-zhang", checkout.getOperator());
        assertNotNull(checkout.getBizTime());
        assertEquals(1, checkout.getDataVersion());

        Long deductionCount = deductionMapper.selectCount(new QueryWrapper<com.evops.apartment.entity.DepositDeduction>()
                .eq("checkout_id", checkout.getId()));
        assertEquals(2L, deductionCount);

        Lease closed = leaseMapper.selectById(lease.getId());
        assertEquals(Lease.STATUS_CLOSED, closed.getStatus());
        assertEquals(2, closed.getDataVersion());
    }

    @Test
    void checkout_rollsBackEverythingWhenAnyStepFails() {
        Lease lease = leaseService.create(buildLeaseReq("HT-RB-0001"));

        CheckoutRequest req = new CheckoutRequest();
        req.setContractNo("HT-RB-0001");
        req.setCheckoutDate(LocalDate.of(2026, 9, 20));
        req.setInspectionResult(LeaseCheckout.RESULT_DAMAGE);
        // item 为 null 触发明细插入失败，验证整体回退
        req.setDeductions(Collections.singletonList(deduction(null, "50.00", null)));
        req.setRequestNo("REQ-RB-0001");
        req.setOperator("op-li");

        assertThrows(Exception.class, () -> checkoutService.checkout(req));

        Long checkoutCount = checkoutMapper.selectCount(new QueryWrapper<LeaseCheckout>()
                .eq("contract_no", "HT-RB-0001"));
        assertEquals(0L, checkoutCount, "失败后不应留下退租记录");
        Lease unchanged = leaseMapper.selectById(lease.getId());
        assertEquals(Lease.STATUS_ACTIVE, unchanged.getStatus(), "失败后租约状态应回退");
        assertEquals(1, unchanged.getDataVersion());
    }

    @Test
    void checkout_rejectsDuplicateAndOverdraw() {
        leaseService.create(buildLeaseReq("HT-DUP-0001"));

        CheckoutRequest req = new CheckoutRequest();
        req.setContractNo("HT-DUP-0001");
        req.setCheckoutDate(LocalDate.of(2026, 9, 21));
        req.setInspectionResult(LeaseCheckout.RESULT_PASS);
        req.setRequestNo("REQ-DUP-0001");
        req.setOperator("op-wang");
        checkoutService.checkout(req);

        req.setRequestNo("REQ-DUP-0002");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> checkoutService.checkout(req));
        assertEquals("租约已退租", ex.getMessage());

        leaseService.create(buildLeaseReq("HT-OVER-0001"));
        CheckoutRequest over = new CheckoutRequest();
        over.setContractNo("HT-OVER-0001");
        over.setCheckoutDate(LocalDate.of(2026, 9, 21));
        over.setInspectionResult(LeaseCheckout.RESULT_DAMAGE);
        over.setDeductions(Collections.singletonList(deduction("赔偿", "99999.00", null)));
        over.setRequestNo("REQ-OVER-0001");
        over.setOperator("op-wang");
        IllegalArgumentException overEx = assertThrows(IllegalArgumentException.class,
                () -> checkoutService.checkout(over));
        assertEquals("扣除总额不能超过押金", overEx.getMessage());
    }

    @Test
    void propertyLedger_registerReverseAndRejectDoubleReverse() {
        PropertyEntryCreateRequest create = new PropertyEntryCreateRequest();
        create.setPropertyCode("PROP-A-101");
        create.setCommunity("示例小区");
        create.setBuilding("1栋");
        create.setRoomNo("101");
        create.setArea(new BigDecimal("45.50"));
        create.setRequestNo("REQ-PROP-0001");
        create.setOperator("op-zhao");
        PropertyLedger entry = propertyLedgerService.register(create);
        assertEquals(PropertyLedger.TYPE_REGISTER, entry.getEntryType());
        assertEquals(1, entry.getDataVersion());

        PropertyReverseRequest reverse = new PropertyReverseRequest();
        reverse.setRefEntryId(entry.getId());
        reverse.setReason("面积登记错误");
        reverse.setRequestNo("REQ-PROP-0002");
        reverse.setOperator("op-zhao");
        PropertyLedger reversal = propertyLedgerService.reverse(reverse);
        assertEquals(PropertyLedger.TYPE_REVERSE, reversal.getEntryType());
        assertEquals(entry.getId(), reversal.getRefEntryId());

        PropertyReverseRequest again = new PropertyReverseRequest();
        again.setRefEntryId(entry.getId());
        again.setReason("重复冲销");
        again.setRequestNo("REQ-PROP-0003");
        again.setOperator("op-zhao");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> propertyLedgerService.reverse(again));
        assertEquals("该台账记录已冲销", ex.getMessage());

        List<PropertyLedger> entries = propertyLedgerService.list("PROP-A-101");
        assertEquals(2, entries.size(), "台账只增不改：登记单与冲销单各一条");
        assertEquals(PropertyLedger.TYPE_REGISTER, entries.get(0).getEntryType());
        assertEquals(PropertyLedger.TYPE_REVERSE, entries.get(1).getEntryType());
    }

    private LeaseCreateRequest buildLeaseReq(String contractNo) {
        LeaseCreateRequest req = new LeaseCreateRequest();
        req.setContractNo(contractNo);
        req.setPropertyCode("PROP-A-101");
        req.setTenantName("张三");
        req.setTenantPhone("13800000000");
        req.setLeaseStart(LocalDate.of(2026, 1, 1));
        req.setLeaseEnd(LocalDate.of(2026, 12, 31));
        req.setMonthlyRent(new BigDecimal("3000.00"));
        req.setDeposit(new BigDecimal("1000.00"));
        req.setRequestNo("REQ-" + contractNo);
        req.setOperator("op-test");
        return req;
    }

    private DeductionItemRequest deduction(String item, String amount, String remark) {
        DeductionItemRequest d = new DeductionItemRequest();
        d.setItem(item);
        d.setAmount(new BigDecimal(amount));
        d.setRemark(remark);
        return d;
    }
}
