package com.company.codeinsight.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文档生成预算 / HTTP 超时；文档与模块层级共用 acquire-wait。
 */
@Data
@Component
@ConfigurationProperties(prefix = "code-insight.ai.doc")
public class AiDocBudgetProperties {

    /**
     * 整段 prompt 字符目标上限：仅在「上下文超限」失败后裁剪时使用（首调不预裁）。
     */
    private int maxPromptChars = 100_000;

    /**
     * 单个 // === Class === 块正文上限；超出截断并标注 truncated。
     */
    private int maxMethodBodyChars = 12_000;

    /**
     * AI HTTP 请求超时（秒）。文档生成偏长，默认 120（原硬编码 45）。
     */
    private int httpTimeoutSeconds = 120;

    /**
     * 重试时每次将源码预算缩到当前的该比例（相对「上一次源码长度」或配置上限）。
     */
    private double shrinkFactor = 0.5;

    /**
     * 单机文档生成并行度（固定线程池大小）。任务内功能文档排队，不超额创建线程。
     */
    private int parallelism = 4;

    /**
     * 文档与模块层级等待集群/本机 AI 并发槽的最长时间（秒）。
     * <p>应明显大于 {@link #httpTimeoutSeconds}；默认 1800（30 分钟）。
     * {@link #acquireWaitUnlimited}=true 时忽略本字段。
     * 层级超时后由 PipelineAiCaller 按普通重试消耗 attempt；用尽后放弃当前入口。</p>
     */
    private int acquireWaitSeconds = 1800;

    /**
     * 等 AI 并发槽时的轮询间隔（毫秒）。默认 5000；过短只会空转抢锁。
     */
    private long acquirePollIntervalMs = 5_000L;

    /**
     * 为 true 时文档/模块层级等 AI 槽无超时，一直轮询直到抢到；
     * 仍响应 {@link InterruptedException}（抛出后由 PipelineAiCaller 进入下一 attempt）。
     */
    private boolean acquireWaitUnlimited = false;
}
