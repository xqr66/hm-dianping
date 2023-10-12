package com.hmdp.utils;

import lombok.AllArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @ClassName RedisIdQWorker
 * @Description
 * @Author xqr
 * @Date 2023/8/12 20:53
 * @Version 1.0
 */
@AllArgsConstructor
@Component
public class RedisIdWorker {
    //开始的时间戳
    private static final long  BEGIN_TIMESTAMP = 1672531200L;//2023-01-01

    //序列号的位数
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * @description: 对业务创造一个全局唯一的id
     * @param: keyPrefix 业务的名称
     * @return: long
     * @date: 2023/8/12 21:05
     */

    public long nextId(String keyPrefix) {
        //1. 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);//现在时间
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;
        //2. 生成序列号
        //2.1 获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        //3. 拼接并返回
        return timeStamp << COUNT_BITS | count;
        //先将时间戳左移，再与count计数器或运算组成64位唯一id，这里假设count不超过32位
    }

}
