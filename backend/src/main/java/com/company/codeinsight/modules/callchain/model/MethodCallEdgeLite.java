package com.company.codeinsight.modules.callchain.model;

import lombok.Data;

/**
 * 调用边轻量投影：入口识别构图 / 路径索引用，避免加载完整 {@code MethodCall} 实体。
 */
@Data
public class MethodCallEdgeLite {

    private Long id;
    private String className;
    private String dependencyName;
    private String filePath;
}
