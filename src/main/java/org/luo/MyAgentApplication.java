package org.luo;

import org.luo.config.PromptProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@MapperScan("org.luo.mapper")
@EnableConfigurationProperties(PromptProperties.class)
public class MyAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyAgentApplication.class, args);
    }

}
