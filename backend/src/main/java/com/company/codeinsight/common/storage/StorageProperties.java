package com.company.codeinsight.common.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 存储路径原始配置（仅非 dev 必填）。
 * <p>实际生效根目录由 {@link EnvStorageResolver} 按 {@code code-insight.env} 解析，
 * 业务代码禁止直读本类空默认值，应走 resolver 门面。</p>
 *
 * <p>两个独立根：
 * <ul>
 *   <li>{@code runtime-root} — 运行期合并根：内含 data/（草稿/AI 日志/增量影响）与
 *       workspaces/（源码 clone + 推送前 docs 组装）两个子目录，由 resolver 派生；</li>
 *   <li>{@code releases-root} — 已发布知识根（知识查看），独立卷以便备份/快照。</li>
 * </ul></p>
 *
 * <pre>
 * code-insight:
 *   storage:
 *     runtime-root: ${STORAGE_RUNTIME_ROOT:}
 *     releases-root: ${STORAGE_RELEASES_ROOT:}
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "code-insight.storage")
public class StorageProperties {

    /** 运行期合并根（含 data/ + workspaces/）；非 dev 必填绝对路径 */
    private String runtimeRoot = "";

    /** 已发布知识根（知识查看）；非 dev 必填绝对路径 */
    private String releasesRoot = "";
}
