package com.company.codeinsight.modules.callchain.model;

/**
 * 反向 BFS 命中：入口类 + 入口方法签名。
 */
public record EntryMethodHit(String entryClassName, String entryMethodSignature) {
}
