package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.jmx.export.UnableToRegisterMBeanException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    //缓存重建的执行器
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
//        解决缓存穿透
//        Shop shop = queryWithPassThrough(id);

//        互斥锁解决缓存击穿问题
//        Shop shop = queryWithMutex(id);

//        逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);

        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, LOCK_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

   private Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1. 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2. 判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //3. 不存在，直接返回
            return null;
        }
        //4.存在，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1 未过期，直接返回店铺信息
            return shop;
        }
        //5.2 已经过期，需要缓存重建
        //6.缓存重建
        String lockKey = LOCK_SHOP_KEY + id;
        //6.1 获取互斥锁
        boolean isLock = tryLock(lockKey);
        //6.2 判断是否获取互斥锁成功
        if (isLock) {
            //6.3 获取互斥锁成功，应该先判断redis中是否已经有了该缓存
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(shopJson)) {
                RedisData redisData2 = JSONUtil.toBean(shopJson, RedisData.class);
                JSONObject data2 = (JSONObject) redisData.getData();
                Shop shop2 = JSONUtil.toBean(data, Shop.class);
                return shop2;
            }
            //6.4 redis中仍然不存在该缓存，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    this.saveShopToRedis(id, 20L);
                } catch (Exception e) {
                    e.printStackTrace();
                }finally{
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //6.5返回过期的商铺信息
            return shop;
    }

    /*public Shop queryWithPassThrough(Long id) {
        //1.从redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id.toString();
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {//null ""都不成立
            //3.存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if (shopJson != null) {//shopJson == "",表示数据库中不存在该数据
            //返回一个错误信息
            return null;
        }
        //4.不存在，根据id查询数据库
        Shop shop = getById(id);

        //5.不存在，返回错误
        if (shop == null) {
            //将空值写入Redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;

    }*/

    /**
     * @description: 运用互斥锁   解决缓存穿透的查询店铺
     * @param: id
     * @return: com.hmdp.entity.Shop
     * @date: 2023/8/11 22:32
     */

   /* public Shop queryWithMutex(Long id) {
        //1.从redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id.toString();

        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {//null ""都不成立
            //3.存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if (shopJson != null) {//shopJson == "",表示数据库中不存在该数据
            //返回一个错误信息
            return null;
        }

        //4. 实现缓存重建
        //4.1 获取互斥锁
        String lockKey = "LOCK_SHOP_KEY" + id;
        Shop shop = null;
        try {

            //4.2 判断是否获取成功
            while (tryLock(lockKey)) {
                //4.3 失败，休眠并重试
                Thread.sleep(50);
            }
            //获取互斥锁后，进行二次校验，如果缓存中已经存在，不需要重建缓存
            shopJson = stringRedisTemplate.opsForValue().get(key);

            if (StrUtil.isNotBlank(shopJson)) {//null ""都不成立
                //缓存中已经存在，直接返回
                shop = JSONUtil.toBean(shopJson, Shop.class);
                return shop;
            }
            //4.4缓存中仍然不存在，根据id查询数据库

            shop = getById(id);

            //5.不存在，返回错误
            if (shop == null) {
                //将空值写入Redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.存在，写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7. 释放互斥锁
            unlock(lockKey);
        }

        //8. 返回
        return shop;

    }*/

    /**
     * @description: 获取互斥锁
     * @param: key
     * @return: boolean
     * @date: 2023/8/11 20:48
     */

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);//flag可能为null，所以需要用工具类方法，只有为true才返回true
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    public void saveShopToRedis(Long id, Long expireSeconds) {
        //1.查询店铺数据
        Shop shop = getById(id);

        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        //3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }


    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1. 判断是否需要根据坐标查询店铺
        if(x == null || y == null) {
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //2. 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        //3. 查询redis、按照距离排序、分页。 结果返回shopId 和 distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()//GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(100000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance()
                                .limit(end)
                );
        //4. 解析出店铺id
        if(results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        //4.1 截取from ~ end 部分
        List<Long> ids = new ArrayList<>(list.size());//保存店铺的id
        if(list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            //4.2 获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            //4，3 获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });

        //5. 依据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD (id," + idStr + ")").list();
        for(Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }

        return Result.ok(shops);

    }
}
