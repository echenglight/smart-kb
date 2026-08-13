package com.smartkb;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * SmartKB · AI 智能知识库问答系统
 *
 * Spring Boot 3 + Spring AI + RAG 检索增强 + 向量检索
 *
 * 启动后:
 *   管理台   http://localhost:8081
 *   接口文档 http://localhost:8081/swagger-ui.html
 *   H2 控制台 http://localhost:8081/h2-console
 * 演示账号: demo / 123456
 */
@EnableAsync              // 文档索引(解析→分块→向量化)走异步线程池, 上传接口立即返回
@MapperScan("com.smartkb.**.mapper")
@SpringBootApplication
public class SmartKbApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartKbApplication.class, args);
    }
}
