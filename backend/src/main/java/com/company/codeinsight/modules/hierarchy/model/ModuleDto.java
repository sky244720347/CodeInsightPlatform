package com.company.codeinsight.modules.hierarchy.model;

import com.company.codeinsight.common.util.jackson.YnBooleanJson;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模块级 DTO
 * 与 analyze_prompt.md 输出的 module 节点对应
 */
@Data
public class ModuleDto {

    /** 5位 Base62 ID（m 前缀） */
    private String id;

    /** 模块名（业务领域/场景，向最大公约数抽象） */
    private String moduleName;

    /** 关键词列表（只使用名词，3-5个） */
    private List<String> keywords = new ArrayList<>();

    /**
     * 人工逐项复核确认标记：JSON 中以 "Y" / "N" 字符串形式呈现
     * <p>前端 JSON tab 中可直接看到哪些模块已被用户复核过</p>
     */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Boolean confirmed;

    @JsonIgnore
    public Boolean getConfirmed() {
        return confirmed;
    }

    @JsonIgnore
    public void setConfirmed(Boolean confirmed) {
        this.confirmed = confirmed;
    }

    @JsonGetter("confirmed")
    public String getConfirmedYn() {
        return YnBooleanJson.format(confirmed);
    }

    @JsonSetter("confirmed")
    public void setConfirmedYn(String raw) {
        this.confirmed = YnBooleanJson.parse(raw);
    }

    /** 子模块列表（按 sub_module_id 索引） */
    private Map<String, SubModuleDto> subModules = new LinkedHashMap<>();

    /**
     * v1: INCREMENTAL 任务的"AI 重提炼"标识（仅 FUNCTION 级节点有值）
     * <p>MODULE 级节点的 sourceEntryClass 通常为 null；FUNCTION 级节点的 sourceEntryClass
     * = 本次 AI 重提炼的入口类全限定名。Phase 4 UI 用此字段做"AI 重提炼"vs"基线继承"分类。</p>
     */
    private String sourceEntryClass;

    /**
     * v1: 模块级 diff 标识（new / modified / unchanged / deleted）
     * <p>前端 Phase 4 DIFF 视图用此字段做 4 组分类。INITIAL 任务全部 null。</p>
     */
    private String diffStatus;
}
