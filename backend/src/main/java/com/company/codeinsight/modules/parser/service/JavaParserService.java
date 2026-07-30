package com.company.codeinsight.modules.parser.service;

import com.company.codeinsight.modules.parser.model.ParsedClassInfo;
import java.io.File;
import java.util.List;

/**
 * Java 静态语法分析解析服务接口
 * 负责通过 JavaParser 解析 Java 源文件的抽象语法树（AST），提取出其结构化元数据。
 */
public interface JavaParserService {

    /**
     * 对单个 Java 源文件进行语法分析
     *
     * @param file 物理 Java 文件对象
     * @return 返回提取出来的 ParsedClassInfo 类信息对象，若解析失败或非合法 Java 类则返回 null
     */
    ParsedClassInfo parseFile(File file);

    /**
     * 递归遍历解析指定目录下的所有 Java 文件并返回解析结果元数据集合
     *
     * @param directory 被分析的目标根目录
     * @return 解析完成的类信息元数据列表
     */
    List<ParsedClassInfo> parseDirectory(File directory);

    /**
     * 释放指定任务相关的解析缓存（parseCache / SymbolSolver / subtypeIndex），
     * 避免任务结束后仍占用堆内存。幂等。
     *
     * @param taskId 任务 ID；null 时无操作
     */
    default void evictTaskCaches(Long taskId) {
        // 默认空实现，供测试桩等轻量实现使用
    }

    /**
     * 清空全部解析侧缓存（实例 parseCache + 静态 SymbolSolver/subtype 索引）。
     * 运维排障 / 内存告警时使用；会打断进行中解析任务的缓存命中。
     */
    default void clearAllCaches() {
        // 默认空实现
    }

    /** 解析缓存规模摘要，便于日志与排障。 */
    default String cacheStatsSummary() {
        return "n/a";
    }
}

