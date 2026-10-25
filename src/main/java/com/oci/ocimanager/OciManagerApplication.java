package com.oci.ocimanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.logging.Level;
import java.util.logging.Logger;

@SpringBootApplication
@EnableScheduling
// 🌟 纯轻量化运行：零 Redis 依赖，采用极简高效 SQLite 与本地内存缓存 
public class OciManagerApplication {

    public static void main(String[] args) {
        // 🌟 【内存优化】关闭 OCI SDK 内部的 Jersey/Apache 日志与警告，减少 Log 对象分配
        System.setProperty("oci.javasdk.jersey.client.default.connector.stream.warning.disabled", "true");
        System.setProperty("oci.javasdk.apacheconnector.stream.warning.disabled", "true");
        Logger.getLogger("com.oracle.bmc.core.VirtualNetworkClient").setLevel(Level.SEVERE);
        // 🌟 【内存优化】禁用 OCI SDK 的 HttpLoggingFilter，大幅减少无用字符串对象分配
        System.setProperty("oci.javasdk.extra.logging.disabled", "true");
        // 🌟 【内存优化】Lettuce (Redis客户端) 底层是 Netty，限制其 IO 线程数避免过度占用内存
        System.setProperty("io.netty.eventLoopThreads", "2");
        // 🌟 【内存优化】限制 Netty 直接内存 (DirectByteBuffer) 上限为 32MB（与 JVM 参数 -Dio.netty.maxDirectMemory 保持一致）
        System.setProperty("io.netty.maxDirectMemory", "33554432");

        // 🌟 【内存/线程泄露修复】全局约束 OCI SDK 底层 Jersey3 客户端异步线程池大小为 1，杜绝多 Region/多账号产生几百个 jersey-client-async-executor 线程
        System.setProperty("jersey.config.client.async.threadPoolSize", "1");
        System.setProperty("org.glassfish.jersey.client.ClientProperties.ASYNC_THREADPOOL_SIZE", "1");
        System.setProperty("jersey.config.client.suppressHttpUrlConnectorThreadPoolWarning", "true");
        
        // 🌟 【线程泄露核心修复】禁用 Apache HTTP 客户端为每个 OCI 客户端实例单独创建的空闲连接监控线程 (idle-connection-monitor)，杜绝 pthread_create failed (EAGAIN)
        System.setProperty("oci.javasdk.apache.idle.connection.monitor.thread.enabled", "false");
        // 🌟 【备用手段】如无必要尽量使用 Java 默认 HttpURLConnection 避免 Apache 连接池开销
        System.setProperty("OCI_JAVASDK_JERSEY_CLIENT_DEFAULT_CONNECTOR_ENABLED", "true");
        
        // 🌟 【线程泄露补充修复】允许 OCI SDK 内部 Jersey 异步执行器的核心线程超时销毁，解决 jersey-client-async-executor 堆积问题
        System.setProperty("oci.javasdk.sync.requests.async.core.thread.timeout.enabled", "true");
        System.setProperty("oci.javasdk.async.requests.async.core.thread.timeout.enabled", "true");

        SpringApplication.run(OciManagerApplication.class, args);
        System.out.println("===========================================");
        System.out.println("🚀 OCIBox (Java + SQLite 纯轻量版) 启动成功！");
        System.out.println("===========================================");
    }

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // 🌟 【内存优化】定时任务调度池从 5 缩减到 2，已足够（定时任务已错峰执行）
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("OciTasks-");
        // 🌟 【内存优化】等待任务完成后才关闭，防止服务停止时任务被强杀产生脏数据
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(15);
        scheduler.initialize();
        return scheduler;
    }
}
