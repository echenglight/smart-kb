package com.smartkb.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 线程池配置
 *
 * indexExecutor: 文档索引专用(解析→分块→向量化耗时可达分钟级, 不能占用 Tomcat 请求线程)。
 * chatExecutor:  SSE 问答流水线专用(检索+改写+重排是阻塞调用, 先在此执行, 再交给流式响应)。
 *
 * 分离两个线程池：索引是重 IO 任务，问答要求低延迟，
 * 混用一个池会让批量上传文档把问答请求全部堵死(资源隔离/舱壁模式)。
 */
@Configuration
public class AsyncConfig {

    @Bean("indexExecutor")
    public ThreadPoolTaskExecutor indexExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("kb-index-");
        executor.initialize();
        return executor;
    }

    @Bean("chatExecutor")
    public ThreadPoolTaskExecutor chatExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("kb-chat-");
        executor.initialize();
        return executor;
    }
}
