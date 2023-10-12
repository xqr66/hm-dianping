package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @ClassName RedissonConfig
 * @Description
 * @Author xqr
 * @Date 2023/8/23 9:37
 * @Version 1.0
 */
@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient() {
        //配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:6379");
        //创建RedissonClient对象
        return Redisson.create(config);
    }
}
