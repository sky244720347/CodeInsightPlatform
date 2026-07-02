package com.company.codeinsight.modules.entrypoint.model;

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

    public List<String> getEffectiveIncludeAnnotations() {
        return includeAnnotations == null ? List.of() : includeAnnotations;
    }

    public List<String> getEffectiveIncludeClasspaths() {
        return includeClasspaths == null ? List.of() : includeClasspaths;
    }

    public List<String> getEffectiveIncludeExtends() {
        return includeExtends == null ? List.of() : includeExtends;
    }

    public boolean isEmpty() {
        return getEffectiveIncludeAnnotations().isEmpty()
                && getEffectiveIncludeClasspaths().isEmpty()
                && getEffectiveIncludeExtends().isEmpty();
    }
}
