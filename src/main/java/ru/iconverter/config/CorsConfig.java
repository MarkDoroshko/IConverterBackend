package ru.iconverter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        // Только боевой origin. IP-адрес сервера с портом 7777
                        // (и по HTTP) из allow-list убран: за ним нет ничего,
                        // кроме самого сервера, а plain-HTTP origin ослаблял
                        // политику без пользы.
                        .allowedOrigins("https://iconverter.ru", "https://www.iconverter.ru")
                        .allowedMethods("GET", "POST", "OPTIONS")
                        .allowedHeaders("*")
                        // Cookie/сессии не используются — credentials не нужны,
                        // и с false нельзя случайно открыть CORS-доступ к ним.
                        .allowCredentials(false)
                        .maxAge(3600);
            }
        };
    }
}
