package com.company.codeinsight.modules.push.enums;

/**
 * 知识推送方式。当前仅支持 NAS 发布到 releasesRoot。
 */
public enum PushMethod {

    /** NAS 共享文件系统：复制到 {releasesRoot}/{sys}/{repo}/{ver}/ */
    NAS
}
