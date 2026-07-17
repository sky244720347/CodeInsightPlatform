package com.company.codeinsight.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 框架配置类
 * 包含分页拦截器配置，以及用于审计字段自动填充的 MetaObjectHandler。
 */
@Configuration
public class MyBatisPlusConfig implements MetaObjectHandler {

    private static final String SYS_USER = "sys";

    /**
     * 配置 MyBatis-Plus 拦截器链
     * 注册针对 PostgreSQL 数据库的分页拦截器，使 MyBatis-Plus 的 Page 分页查询能自动拼装 LIMIT/OFFSET 语句。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }

    /**
     * 数据库新增记录时的字段自动填充拦截器
     * 在调用 mapper.insert() 时，自动为实体类中标记了 TableField(fill = FieldFill.INSERT) 的字段填充初始值。
     *
     * @param metaObject 元数据反射对象
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        String user = currentUserOrSys();
        this.strictInsertFill(metaObject, "isDeleted", Integer.class, 0);
        this.strictInsertFill(metaObject, "createdBy", String.class, user);
        this.strictInsertFill(metaObject, "updatedBy", String.class, user);
        this.strictInsertFill(metaObject, "createdDate", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updatedDate", LocalDateTime.class, LocalDateTime.now());
    }

    /**
     * 数据库更新记录时的字段自动填充拦截器
     * 在调用 mapper.update() 或 updateById() 时，自动为实体中标记了 TableField(fill = FieldFill.UPDATE) 的字段填充更新值。
     *
     * @param metaObject 元数据反射对象
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedBy", String.class, currentUserOrSys());
        this.strictUpdateFill(metaObject, "updatedDate", LocalDateTime.class, LocalDateTime.now());
    }

    /** MVP 无登录态时恒返回 sys；后续接 Auth 再取当前用户。 */
    private String currentUserOrSys() {
        return SYS_USER;
    }
}
