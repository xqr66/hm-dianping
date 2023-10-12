package com.hmdp;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

//    @Resource
//    private ExecutorService executorService = Executors.newFixedThreadPool(500);

    @Test
    void testIdWorker() {
        for (int i = 0; i < 100; i++) {
            long id = redisIdWorker.nextId("order");
            System.out.println(id);

        }
    }

    @Test
    void testSaveShop() {
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
    }

    @Test
    void saveShopToRedis() {

            //1.查询店铺数据
        for(int i = 1; i <= 14; i++) {
            Shop shop = shopService.getById(i);

            //2.封装逻辑过期时间
            RedisData redisData = new RedisData();
            redisData.setData(shop);
            redisData.setExpireTime(LocalDateTime.now().plusSeconds(300));

            //3.写入Redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + i, JSONUtil.toJsonStr(redisData));
        }


    }
    @Test
    void loadShopData() {
        //1. 查询店铺信息
        List<Shop> shops = shopService.list();
        //2. 将店铺按照typeId 分类，typeId一致的分到同一组中
        Map<Long, List<Shop>> map = new HashMap<>();
        map = shops.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //3. 分批导入redis中
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            //3.1 获取typeId
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            //3.2 获取该typeId的所有店铺
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            for (Shop shop : value) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }

    }


}
