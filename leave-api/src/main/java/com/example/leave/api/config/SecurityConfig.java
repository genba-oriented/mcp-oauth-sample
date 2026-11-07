package com.example.leave.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 休暇申請API as an OAuth 2.0 Resource Server.
 *
 * <p>JWT の検証（署名・issuer・audience）は application.yml の {@code spring.security.oauth2.resourceserver.jwt.*}
 * で Spring Boot に任せる。検証済みの JWT を誰（{@code preferred_username}）・何ができるか（{@code ROLE_*}）に
 * するのは {@link EmployeeAuthenticationConverter}。ロールはトークンではなく、このアプリの社員マスタから決める。
 * ここで決めるのは「どのロールで何が呼べるか」だけ。どの部下の申請かは {@code LeaveService} が判定する。
 *
 * <p>scope による制限は掛けない。誰経由で呼ばれても、ユーザー本人のロールの範囲で動く。
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, EmployeeAuthenticationConverter authenticationConverter)
            throws Exception {
        http
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/approvals/**").hasRole("manager")
                .anyRequest().hasRole("employee")
            )
            // これが無いと、トークン無しの 401 で（ログイン後に戻るための）HTTP セッションが作られる。
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter)));
        return http.build();
    }
}
