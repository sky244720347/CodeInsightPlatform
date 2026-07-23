package com.company.codeinsight.modules.entrypoint.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 某一入口类型（Controller / Job / MQ / Other）独立的 include 规则。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TypeIncludeRules implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<String> includeAnnotations = new ArrayList<>();
    private List<String> includeClasspaths = new ArrayList<>();
    private List<String> includeExtends = new ArrayList<>();

    @JsonIgnore
    public List<String> getEffectiveIncludeAnnotations() {
        return includeAnnotations == null ? List.of() : includeAnnotations;
    }

    @JsonIgnore
    public List<String> getEffectiveIncludeClasspaths() {
        return includeClasspaths == null ? List.of() : includeClasspaths;
    }

    @JsonIgnore
    public List<String> getEffectiveIncludeExtends() {
        return includeExtends == null ? List.of() : includeExtends;
    }

    /** 业务判断用；禁止序列化成 JSON 字段 {@code empty}，否则 decode 会失败并回退默认配置。 */
    @JsonIgnore
    public boolean isEmpty() {
        return getEffectiveIncludeAnnotations().isEmpty()
                && getEffectiveIncludeClasspaths().isEmpty()
                && getEffectiveIncludeExtends().isEmpty();
    }
}
