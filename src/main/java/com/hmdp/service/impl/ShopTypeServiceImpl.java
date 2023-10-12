package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_LIST_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryShopType() {
        //1.从Redis中查询商铺缓存
        List<String> shopTypeJsonList = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_LIST_KEY, 0, -1);
        //2.判断Redis中是否有该缓存
        if(shopTypeJsonList != null && !shopTypeJsonList.isEmpty()) {
            //2.1若Redis中存在该缓存，则直接返回
            ArrayList<ShopType> typeList = new ArrayList<>();
            for(String str : shopTypeJsonList) {
                typeList.add(JSONUtil.toBean(str,ShopType.class));
            }
            return Result.ok(typeList);
        }
        //2.2若Redis中不存在该缓存，区数据库中查找
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //3.判断数据库中是否存在
        if(typeList == null || typeList.isEmpty()) {
            //3.1数据库中不存在，则返回false
            return Result.fail("分类不存在");
        }
        //3.2当数据库中数据存在，存入Redis中
        for(ShopType shopType : typeList) {
            //每一个店铺类型都是实体类，需要转化为Json数据类型
            stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_TYPE_LIST_KEY, JSONUtil.toJsonStr(shopType));
        }
        //3.3返回
        return Result.ok(typeList);
    }
}
