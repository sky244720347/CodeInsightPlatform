package com.company.codeinsight.modules.entrypoint.trial;

import com.company.codeinsight.modules.entrypoint.model.DiscoveredEntrypoint;
import com.company.codeinsight.modules.entrypoint.model.EntryPointConfig;

import java.util.List;

public interface TrialRunService {

    /**
     * 触发试跑
     * <p>同一仓库同一时刻只允许一个试跑执行；用 Redis 锁串行化</p>
     *
     * @return 试跑记录（含 PENDING 初始状态），前端轮询 /trial/{id} 拿结果
     */
    EntryScanTrialEntity trigger(Long systemId, Long repositoryId, EntryPointConfig config, String operator);

    /**
     * 异步执行试跑主体（拉代码 + AST + 入口识别 + 写结果）
     */
    void executeAsync(Long trialId);

    /**
     * 查询试跑结果
     */
    EntryScanTrialEntity get(Long trialId);

    /**
     * 查询仓库当前是否有进行中的试跑
     */
    boolean isLocked(Long repositoryId);

    /**
     * 取消试跑：仅在 PENDING/RUNNING 状态可取消
     */
    boolean cancel(Long trialId, String operator);

    /**
     * 解析 config_snapshot 字符串回 EntryPointConfig（供前端回填使用）
     */
    EntryPointConfig parseConfigSnapshot(String configSnapshot);

    /**
     * 解析 result_json 字符串回 List&lt;DiscoveredEntrypoint&gt;（供前端结果展示）
     */
    List<DiscoveredEntrypoint> parseResultEntries(String resultJson);
}
