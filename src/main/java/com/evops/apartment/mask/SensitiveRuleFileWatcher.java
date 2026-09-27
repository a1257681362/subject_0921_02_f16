package com.evops.apartment.mask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

/**
 * 脱敏规则热加载：监听外部规则文件（默认 config/sensitive-rules.yml），
 * CREATE/MODIFY 后去抖重载并整体替换规则快照；DELETE 或文件不存在时回退到 application.yml/内置默认；
 * 文件解析失败只告警、保留当前规则，绝不中断业务。
 *
 * 文件内只配需要覆盖的类型，未出现的类型沿用启动时的默认底；
 * 但同一类型的规则视为整体（keepPrefix/keepSuffix/maskChar 需写全）。
 */
@Component
public class SensitiveRuleFileWatcher implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(SensitiveRuleFileWatcher.class);
    /** 去抖窗口：编辑器保存常触发连续 MODIFY 事件，静默这么久后重载一次。 */
    private static final long QUIET_PERIOD_MS = 300;

    private final SensitiveRuleRegistry registry;
    private final SensitiveRuleProperties baseline;
    private final Path ruleFile;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private WatchService watchService;
    private Thread watchThread;

    public SensitiveRuleFileWatcher(SensitiveRuleRegistry registry, SensitiveRuleProperties baseline) {
        this.registry = registry;
        this.baseline = baseline;
        this.ruleFile = Paths.get(baseline.getRuleFile()).toAbsolutePath().normalize();
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        reload("启动加载");
        watchThread = new Thread(this::watchLoop, "sensitive-rule-watcher");
        watchThread.setDaemon(true);
        watchThread.start();
        log.info("脱敏规则热加载已启用，监听文件: {}", ruleFile);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
                // 关闭即结束，异常无需处理
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void watchLoop() {
        Path dir = ruleFile.getParent();
        while (running.get()) {
            try {
                if (!Files.isDirectory(dir)) {
                    Thread.sleep(2000); // 目录尚未创建，稍后再试
                    continue;
                }
                watchService = dir.getFileSystem().newWatchService();
                dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
                Long lastSignature = currentSignature();
                while (running.get()) {
                    WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                    boolean eventTouched = false;
                    if (key != null) {
                        for (WatchEvent<?> event : key.pollEvents()) {
                            if (event.context() != null
                                    && ruleFile.getFileName().toString().equals(event.context().toString())) {
                                eventTouched = true;
                            }
                        }
                        key.reset();
                    }
                    // 事件之外再比对一次文件签名：连续写入时 inotify 可能合并/漏投删除事件，
                    // 轮询保证最终一定能收敛到文件实际状态
                    Long signature = currentSignature();
                    if (eventTouched || !signature.equals(lastSignature)) {
                        waitQuietPeriod();
                        reload("文件变更");
                        lastSignature = currentSignature();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                return; // stop() 关闭 WatchService，监听线程正常退出
            } catch (IOException e) {
                if (running.get()) {
                    log.warn("脱敏规则监听异常，2 秒后重试: {}", e.getMessage());
                    sleepQuietly();
                }
            }
        }
    }

    /** 文件签名：存在用“最后修改时间”，不存在记 -1。任何变化都触发重载。 */
    private Long currentSignature() {
        try {
            return Files.exists(ruleFile) ? Files.getLastModifiedTime(ruleFile).toMillis() : -1L;
        } catch (IOException e) {
            return -1L;
        }
    }

    private void waitQuietPeriod() {
        long deadline = System.currentTimeMillis() + QUIET_PERIOD_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                WatchKey more = watchService.poll(50, TimeUnit.MILLISECONDS);
                if (more != null) {
                    more.reset();
                    deadline = System.currentTimeMillis() + QUIET_PERIOD_MS;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** 加载规则文件并热更新；任何失败都保留旧规则。包内可见便于测试直接触发。 */
    synchronized void reload(String reason) {
        try {
            SensitiveRuleProperties merged = baseline.copy();
            if (Files.exists(ruleFile)) {
                YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
                yaml.setResources(new FileSystemResource(ruleFile.toFile()));
                Properties props = yaml.getObject();
                PropertiesPropertySource source = new PropertiesPropertySource("sensitive-rules-file", props);
                Binder binder = new Binder(ConfigurationPropertySources.from(source));
                binder.bind("evops.sensitive", Bindable.ofInstance(merged));
                log.info("脱敏规则{}：已合并外部文件 {}", reason, ruleFile);
            } else {
                log.info("脱敏规则{}：外部文件不存在，使用默认规则 {}", reason, ruleFile);
            }
            validate(merged);
            registry.reload(merged);
        } catch (Exception e) {
            log.warn("脱敏规则{}失败，保留当前规则: {}", reason, e.getMessage());
        }
    }

    private void validate(SensitiveRuleProperties props) {
        for (SensitiveRule rule : props.getRules().values()) {
            if (rule.getKeepPrefix() < 0 || rule.getKeepSuffix() < 0
                    || !StringUtils.hasText(rule.getMaskChar())) {
                throw new IllegalArgumentException("规则非法：保留位数必须非负，遮蔽字符不能为空");
            }
        }
    }
}
