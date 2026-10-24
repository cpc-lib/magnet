package cc.ivera.magnet.infrastructure;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("cc.ivera.magnet.infrastructure.mapper")
public class MyBatisConfig {
}
