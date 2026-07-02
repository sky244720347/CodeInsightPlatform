package com.company.codeinsight.modules.entrypoint.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 指定排除：类全限定名 + 可选方法签名（空表示整类排除）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExcludeTarget implements Serializable {

    private static final long serialVersionUID = 1L;

    private String className;
    /** methodName(ParamTypes)，不含返回类型；null/空 = 整类 */
    private String methodSignature;

    public boolean isClassLevel() {
        return methodSignature == null || methodSignature.isBlank();
    }
}
