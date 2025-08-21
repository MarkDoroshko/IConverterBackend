package ru.iconverter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf().disable()  // отключаем CSRF (для API)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/**").permitAll()  // разрешаем всё в /api/**
                        .anyRequest().permitAll()                // или всё разрешить
                );
        return http.build();
    }
}
