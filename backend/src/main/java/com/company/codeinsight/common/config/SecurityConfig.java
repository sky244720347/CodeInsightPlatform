package com.company.codeinsight.common.config;

import com.company.codeinsight.common.auth.OperatorHeaderFilter;
import com.company.codeinsight.common.net.LocalAddressSet;
import com.company.codeinsight.modules.auth.mapper.UserAccountMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 安全配置类
 * 负责系统接口的安全过滤配置，包括 CSRF 漏洞防护状态配置以及 API 资源的访问权限控制。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 占位过滤器：从 X-Operator header 读取当前操作人并写入 {@link com.company.codeinsight.common.auth.OperatorContext}。
     * 后续接入真鉴权时只需把这里替换为从 Spring Security SecurityContextHolder 读取即可。
     */
    @Bean
    public OperatorHeaderFilter operatorHeaderFilter(UserAccountMapper userAccountMapper,
                                                     LocalAddressSet localAddressSet) {
        return new OperatorHeaderFilter(userAccountMapper, localAddressSet);
    }

    /**
     * 配置安全过滤链 SecurityFilterChain
     * 在此默认放行全部 API 访问请求，供前后端在独立网络沙箱或内部可信环境中流畅进行离线部署与调试。
     *
     * @param http HttpSecurity 安全构建器
     * @return 构建好的 SecurityFilterChain 过滤器链
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, OperatorHeaderFilter operatorHeaderFilter) throws Exception {
        http
            // 禁用 CSRF (跨站请求伪造) 保护，防止非浏览器环境（如 Postman、大模型 HTTP 客户端）调用接口时被拦截拦截
            .csrf(AbstractHttpConfigurer::disable)

            // 在标准 UsernamePasswordAuthenticationFilter 之前插入操作人上下文 filter，
            // 保证 controller / service 层能通过 OperatorContext.get() 拿到当前操作人。
            .addFilterBefore(operatorHeaderFilter, UsernamePasswordAuthenticationFilter.class)

            // 配置请求的权限拦截控制
            .authorizeHttpRequests(authorize -> authorize
                // 放行所有 HTTP 请求（在此项目中暂不做强制的 RBAC 拦截，方便快速离线调试）
                .anyRequest().permitAll()
            );
        return http.build();
    }
}

