package com.smartkb.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger 接口文档: http://localhost:8081/swagger-ui.html */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info()
                .title("SmartKB · AI 智能知识库问答系统")
                .description("Spring Boot 3 + Spring AI + RAG 检索增强 + 向量检索。"
                        + "先调用 /api/auth/login 登录(demo/123456), token 自动写入 Cookie。")
                .version("1.0.0"));
    }
}
