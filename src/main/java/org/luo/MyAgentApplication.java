package org.luo;

import org.luo.properties.MemoryProperties;
import org.luo.properties.PromptProperties;
import org.luo.properties.RagProperties;
import org.luo.properties.VisionProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@MapperScan("org.luo.mapper")
@EnableConfigurationProperties({PromptProperties.class, VisionProperties.class, RagProperties.class,
        MemoryProperties.class})
public class MyAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyAgentApplication.class, args);
    }

}
