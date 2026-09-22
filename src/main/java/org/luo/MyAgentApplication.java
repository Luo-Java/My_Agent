package org.luo;

import org.luo.ai.properties.MemoryProperties;
import org.luo.ai.properties.PromptProperties;
import org.luo.ai.properties.RagProperties;
import org.luo.ai.properties.ToolCallProperties;
import org.luo.ai.properties.VisionProperties;
import org.luo.system.security.JwtProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@MapperScan({"org.luo.ai.mapper", "org.luo.edu.mapper", "org.luo.system.mapper"})
@EnableConfigurationProperties({PromptProperties.class, VisionProperties.class, RagProperties.class,
        MemoryProperties.class, ToolCallProperties.class, JwtProperties.class})
public class MyAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyAgentApplication.class, args);
    }

}
