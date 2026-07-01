package com.company.codeinsight.modules.scanwindow.scheduler;

import com.company.codeinsight.modules.repository.entity.CodeRepository;
import com.company.codeinsight.modules.repository.mapper.CodeRepositoryMapper;
import com.company.codeinsight.modules.scanwindow.entity.ScanWindowEntity;
import com.company.codeinsight.modules.scanwindow.service.ScanWindowService;
import com.company.codeinsight.modules.task.service.DecompileTaskService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * 定时扫描任务调度器：动态 cron + Redis 持久化 + 热更新无需重启。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScanWindowScheduler {

    private static final String REDIS_KEY_CRON = "scan:scheduler:cron";
    private static final String REDIS_KEY_ENABLED = "scan:scheduler:enabled";
    private static final DateTimeFormatter SLOT_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final ScanWindowService scanWindowService;
    private final DecompileTaskService decompileTaskService;
    private final CodeRepositoryMapper repositoryMapper;
    private final TaskScheduler taskScheduler;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Value("${code-insight.scan.cron:0 */1 * * * *}")
    private String defaultCron;

    private volatile boolean enabled = true;
    private volatile String currentCron = "0 */1 * * * *";
    private volatile ScheduledFuture<?> scheduledFuture;

    @PostConstruct
    public void init() {
        // 从 Redis 恢复 cron + enabled
        if (redisTemplate != null) {
            String redisCron = redisTemplate.opsForValue().get(REDIS_KEY_CRON);
            if (redisCron != null && !redisCron.isBlank()) currentCron = redisCron;
            else currentCron = defaultCron;

            String redisEnabled = redisTemplate.opsForValue().get(REDIS_KEY_ENABLED);
            if (redisEnabled != null) enabled = Boolean.parseBoolean(redisEnabled);
        } else {
            currentCron = defaultCron;
        }
        start();
    }

    private void start() {
        if (scheduledFuture != null) scheduledFuture.cancel(false);
        if (!enabled) { log.info("ScanWindowScheduler disabled"); return; }
        try {
            CronExpression.parse(currentCron);
            scheduledFuture = taskScheduler.schedule(this::tick, new CronTrigger(currentCron));
            log.info("ScanWindowScheduler started: cron={} enabled={}", currentCron, enabled);
        } catch (Exception e) {
            log.error("Invalid cron: {}", currentCron, e);
        }
    }

    public String getCurrentCron() { return currentCron; }
    public boolean isEnabled() { return enabled; }

    public List<String> getNextRuns(int count) {
        List<String> out = new ArrayList<>();
        try {
            CronExpression expr = CronExpression.parse(currentCron);
            LocalDateTime t = LocalDateTime.now();
            for (int i = 0; i < count; i++) {
                t = expr.next(t);
                if (t == null) break;
                out.add(t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public void updateCron(String cron) {
        CronExpression.parse(cron); // 校验
        currentCron = cron;
        if (redisTemplate != null) redisTemplate.opsForValue().set(REDIS_KEY_CRON, cron);
        start();
    }

    public void setEnabled(boolean e) {
        enabled = e;
        if (redisTemplate != null) redisTemplate.opsForValue().set(REDIS_KEY_ENABLED, String.valueOf(e));
        if (e) start();
        else { if (scheduledFuture != null) scheduledFuture.cancel(false); scheduledFuture = null; }
    }

    /* ============================== 调度核心 ============================== */

    public void tick() {
        if (!enabled) return;
        LocalDateTime now = LocalDateTime.now();
        int todayBit = dayBit(now.getDayOfWeek());
        int hour = now.getHour();
        int minute = now.getMinute();
        String slot = now.format(SLOT_FMT);

        List<ScanWindowEntity> windows;
        try { windows = scanWindowService.listEnabled(); } catch (Exception e) { return; }
        if (windows.isEmpty()) return;

        int fired = 0;
        for (ScanWindowEntity w : windows) {
            if ((w.getWeekDays() & todayBit) == 0) continue;
            if (!w.getHour().equals(hour)) continue;
            if (!w.getMinute().equals(minute)) continue;

            String lockKey = "scan:fire:" + w.getRepositoryId() + ":" + slot;
            boolean acquired = lockKey != null && tryLock(lockKey);
            if (lockKey != null && !acquired) continue;

            if (w.getLastFiredAt() != null) {
                LocalDateTime last = w.getLastFiredAt();
                if (last.getDayOfWeek() == now.getDayOfWeek()
                        && last.getHour() == hour && last.getMinute() == minute) continue;
            }

            CodeRepository repo = repositoryMapper.selectById(w.getRepositoryId());
            if (repo == null) continue;

            try {
                decompileTaskService.createInitialTask(repo.getSystemId(), repo.getId(),
                        null, null, null, null, true, true, "SCHEDULED");
                fired++;
                ScanWindowEntity upd = new ScanWindowEntity();
                upd.setRepositoryId(w.getRepositoryId());
                upd.setWeekDays(w.getWeekDays());
                upd.setHour(w.getHour());
                upd.setMinute(w.getMinute());
                upd.setEnabled(w.getEnabled());
                upd.setLastFiredAt(now);
                scanWindowService.upsert(upd);
            } catch (Exception e) {
                log.error("scan fire failed repoId={}", w.getRepositoryId(), e);
                if (redisTemplate != null) try { redisTemplate.delete(lockKey); } catch (Exception x) {}
            }
        }
        if (fired > 0) log.info("scan scheduler tick: {} windows fired", fired);
    }

    private boolean tryLock(String key) {
        if (redisTemplate == null) return false;
        try { return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "1", java.time.Duration.ofMinutes(2))); }
        catch (Exception e) { return false; }
    }

    static int dayBit(DayOfWeek d) { return 1 << (d.getValue() - 1); }
}
