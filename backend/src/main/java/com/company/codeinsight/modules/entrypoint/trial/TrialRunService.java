package com.company.codeinsight.modules.entrypoint.trial;

import com.company.codeinsight.common.response.PageResult;
import com.company.codeinsight.modules.entrypoint.model.DiscoveredEntrypoint;
import com.company.codeinsight.modules.entrypoint.model.EntryPointConfig;

import java.util.List;

public interface TrialRunService {

    /**
     * 触发试跑
     * <p>同一仓库同一时刻只允许一个试跑执行；用 Redis 锁 + DB 状态串行化</p>
     */
    EntryScanTrialEntity trigger(Long systemId, Long repositoryId, EntryPointConfig config, String operator);

    /** 异步执行试跑主体（拉代码 + AST + 入口识别 + 写结果） */
    void executeAsync(Long trialId);

    EntryScanTrialEntity get(Long trialId);

    /** 仓库当前进行中的试跑（PENDING/RUNNING 且未超时）；无则 null */
    EntryScanTrialEntity getActive(Long repositoryId);

    /** 仓库最近一次试跑摘要 */
    EntryScanTrialSummary getLatestSummary(Long repositoryId);

    /** 试跑历史分页（按 started_at 倒序） */
    PageResult<EntryScanTrialSummary> listHistory(Long repositoryId, long current, long size);

    /** 查询仓库当前是否有进行中的试跑（Redis 锁或 DB 活跃记录） */
    boolean isLocked(Long repositoryId);

    /** 取消试跑：仅在 PENDING/RUNNING 状态可取消 */
    boolean cancel(Long trialId, String operator);

    EntryPointConfig parseConfigSnapshot(String configSnapshot);

    List<DiscoveredEntrypoint> parseResultEntries(String resultJson);

    /** 对账超时/僵尸试跑（启动与定时任务调用） */
    void reconcileStaleTrials();
}
