package com.evops.apartment.pinyin;

import java.util.List;

/**
 * apt-11 拼音检索验收页数据：样例租约 + 验收用例执行结果。
 * 打印页整页渲染后纸面签字确认（总部巡检验收留痕）。
 */
public class LeaseAcceptanceData {

    /** 一条样例租约（含拼音冗余字段，验收需核对索引内容）。 */
    public static class SampleLease {
        private String contractNo;
        private String propertyCode;
        private String tenantName;
        private String tenantNamePyFull;
        private String tenantNamePyInitials;
        private String leaseStart;
        private String leaseEnd;
        private String monthlyRent;
        private String status;

        public String getContractNo() { return contractNo; }
        public void setContractNo(String contractNo) { this.contractNo = contractNo; }
        public String getPropertyCode() { return propertyCode; }
        public void setPropertyCode(String propertyCode) { this.propertyCode = propertyCode; }
        public String getTenantName() { return tenantName; }
        public void setTenantName(String tenantName) { this.tenantName = tenantName; }
        public String getTenantNamePyFull() { return tenantNamePyFull; }
        public void setTenantNamePyFull(String tenantNamePyFull) { this.tenantNamePyFull = tenantNamePyFull; }
        public String getTenantNamePyInitials() { return tenantNamePyInitials; }
        public void setTenantNamePyInitials(String tenantNamePyInitials) { this.tenantNamePyInitials = tenantNamePyInitials; }
        public String getLeaseStart() { return leaseStart; }
        public void setLeaseStart(String leaseStart) { this.leaseStart = leaseStart; }
        public String getLeaseEnd() { return leaseEnd; }
        public void setLeaseEnd(String leaseEnd) { this.leaseEnd = leaseEnd; }
        public String getMonthlyRent() { return monthlyRent; }
        public void setMonthlyRent(String monthlyRent) { this.monthlyRent = monthlyRent; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    /** 一条验收用例的执行结果。 */
    public static class AcceptanceCase {
        private int no;
        private String category;
        private String input;
        private String expectDesc;
        private List<String> expectedContracts;
        private List<String> actualContracts;
        private boolean pass;

        public int getNo() { return no; }
        public void setNo(int no) { this.no = no; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public String getInput() { return input; }
        public void setInput(String input) { this.input = input; }
        public String getExpectDesc() { return expectDesc; }
        public void setExpectDesc(String expectDesc) { this.expectDesc = expectDesc; }
        public List<String> getExpectedContracts() { return expectedContracts; }
        public void setExpectedContracts(List<String> expectedContracts) { this.expectedContracts = expectedContracts; }
        public List<String> getActualContracts() { return actualContracts; }
        public void setActualContracts(List<String> actualContracts) { this.actualContracts = actualContracts; }
        public boolean isPass() { return pass; }
        public void setPass(boolean pass) { this.pass = pass; }
    }

    private List<SampleLease> samples;
    private List<AcceptanceCase> cases;
    private int passCount;
    private int totalCount;
    private boolean allPass;
    private String generatedAt;

    public List<SampleLease> getSamples() { return samples; }
    public void setSamples(List<SampleLease> samples) { this.samples = samples; }
    public List<AcceptanceCase> getCases() { return cases; }
    public void setCases(List<AcceptanceCase> cases) { this.cases = cases; }
    public int getPassCount() { return passCount; }
    public void setPassCount(int passCount) { this.passCount = passCount; }
    public int getTotalCount() { return totalCount; }
    public void setTotalCount(int totalCount) { this.totalCount = totalCount; }
    public boolean isAllPass() { return allPass; }
    public void setAllPass(boolean allPass) { this.allPass = allPass; }
    public String getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(String generatedAt) { this.generatedAt = generatedAt; }
}
