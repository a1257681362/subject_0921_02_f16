package com.evops.apartment.pinyin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.evops.apartment.entity.Lease;
import com.evops.apartment.mapper.LeaseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * apt-11 历史租约拼音索引回填：旧文件库升级后 {@code tenant_name_py_full} /
 * {@code tenant_name_py_initials} 两列为 NULL（schema.sql 仅做幂等加列），
 * 应用启动时按与建档完全相同的 {@link PinyinIndexer} 口径分批补齐。
 *
 * 幂等：只挑两列任一为 NULL 的租约；每批一个独立事务，中途失败整批回滚、已提交批次不返工，
 * 下轮启动继续。新建档行已在同事务写入冗余字段，不会落到这里。
 */
@Component
@Order(20)
public class PinyinIndexBackfillRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PinyinIndexBackfillRunner.class);

    /** 单批最多处理行数，避免超大体量租约表单批事务过长（演示库通常一批即完）。 */
    private static final int BATCH = 500;

    private final LeaseMapper leaseMapper;
    /** 自注入代理：run() 循环中逐批调用，保证每批是独立事务（自调用会绕过事务代理）。 */
    private final ObjectProvider<PinyinIndexBackfillRunner> self;

    public PinyinIndexBackfillRunner(LeaseMapper leaseMapper,
                                     @Lazy ObjectProvider<PinyinIndexBackfillRunner> self) {
        this.leaseMapper = leaseMapper;
        this.self = self;
    }

    @Override
    public void run(ApplicationArguments args) {
        int total = 0;
        int n;
        do {
            n = self.getObject().backfillBatch();
            total += n;
        } while (n == BATCH);
        if (total > 0) {
            log.info("apt-11 历史租约拼音索引回填完成 共 {} 条", total);
        }
    }

    /** 回填一批，返回本批行数。供测试也可确定性单批调用。 */
    @Transactional(rollbackFor = Exception.class)
    public int backfillBatch() {
        List<Lease> missing = leaseMapper.selectList(new QueryWrapper<Lease>()
                .select("id", "tenant_name")
                .and(w -> w.isNull("tenant_name_py_full").or().isNull("tenant_name_py_initials"))
                .last("LIMIT " + BATCH));
        for (Lease lease : missing) {
            PinyinIndex index = PinyinIndexer.build(lease.getTenantName());
            // 显式 SET 两列（含置 NULL）：updateById 的 NOT_NULL 策略会跳过 null，
            // 且全行除 id 外无更新列时会生成无 SET 子句的非法 SQL
            leaseMapper.update(null, new UpdateWrapper<Lease>()
                    .eq("id", lease.getId())
                    .set("tenant_name_py_full", index.getFull())
                    .set("tenant_name_py_initials", index.getInitials()));
        }
        return missing.size();
    }
}
