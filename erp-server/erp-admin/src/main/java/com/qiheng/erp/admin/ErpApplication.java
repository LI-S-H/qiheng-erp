package com.qiheng.erp.admin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

// 使用 Boot 自带扫描，保留测试配置排除规则，避免额外 ComponentScan 扫入隔离测试 Bean。
@SpringBootApplication(scanBasePackages = "com.qiheng.erp")
@MapperScan("com.qiheng.erp.**.mapper")
@EnableTransactionManagement
@EnableScheduling
public class ErpApplication {

    public static void main(String[] args) {
        SpringApplication.run(ErpApplication.class, args);
    }
}
