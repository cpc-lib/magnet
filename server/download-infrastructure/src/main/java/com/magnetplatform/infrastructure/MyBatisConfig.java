package com.magnetplatform.infrastructure;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.magnetplatform.infrastructure.mapper")
public class MyBatisConfig {
}
