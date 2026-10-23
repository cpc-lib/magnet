package com.magnetplatform.api.security;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

/** 无状态 JWT 安全：/api/v1/auth/** 放行，支持 SSE 异步分发（规格 §4.1） */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/v1/auth/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, res, ex) -> {
                            if (!res.isCommitted()) {
                                res.setStatus(401);
                                res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                                res.setCharacterEncoding(StandardCharsets.UTF_8.name());
                                res.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"未认证或访问令牌无效\"}");
                            }
                        })
                        .accessDeniedHandler((req, res, ex) -> {
                            if (!res.isCommitted()) {
                                res.setStatus(403);
                                res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                                res.setCharacterEncoding(StandardCharsets.UTF_8.name());
                                res.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"无权访问该资源\"}");
                            }
                        }))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
