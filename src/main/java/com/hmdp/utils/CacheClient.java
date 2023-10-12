package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import io.netty.util.Timeout;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;


import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * @ClassName CacheClient
 * @Description
 * @Author xqr
 * @Date 2023/8/12 16:46
 * @Version 1.0
 */

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    //缓存重建的执行器
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * @description: 运用R 和 ID 两个泛型来实现不同类型的查询和不同id类型的查询
     * @param: keyPrefix key的前缀
     * id 要查询的 id
     * type 传入泛型R的类
     * @return: R
     * @date: 2023/8/12 17:00
     */

    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
                                          Long time, TimeUnit unit) {
        //1.从redis查询商铺缓存
        String key = keyPrefix + id.toString();
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(json)) {//null ""都不成立
            //3.存在，直接返回,将json转化为type类也就是传入的R类
            R ret = JSONUtil.toBean(json, type);
            return ret;
        }
        if (json != null) {//shopJson == "",表示数据库中不存在该数据
            //返回一个错误信息
            return null;
        }
        //4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);

        //5.不存在，返回错误
        if (r == null) {
            //将空值写入Redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在，写入redis
        this.set(key, r, time, unit);
        return r;

    }

    public <R, ID> R queryWithLogicalExpire(String keyPrefix, String lockPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
                                            Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1. 从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        //2. 判断是否存在
        if (StrUtil.isBlank(json)) {
            //3. 不存在，直接返回
            return null;
        }
        //4.存在，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1 未过期，直接返回店铺信息
            return r;
        }
        //5.2 已经过期，需要缓存重建
        //6.缓存重建
        String lockKey = lockPrefix + id;
        //6.1 获取互斥锁
        boolean isLock = tryLock(lockKey);
        //6.2 判断是否获取互斥锁成功
        if (isLock) {
            //6.3 获取互斥锁成功，应该先判断redis中是否已经有了该缓存
            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                RedisData redisData2 = JSONUtil.toBean(json, RedisData.class);
                JSONObject data2 = (JSONObject) redisData2.getData();
                R r1 = JSONUtil.toBean(data2, type);
                if(redisData2.getExpireTime().isAfter(LocalDateTime.now())) {
                    return r1;
                }
            }
            //6.4 redis中仍然不存在该缓存，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    R r2 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, r2, time, unit);

                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //6.5返回过期的商铺信息
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);//flag可能为null，所以需要用工具类方法，只有为true才返回true
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
