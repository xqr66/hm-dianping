package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1. 获取用户
        Long userId = UserHolder.getUser().getId();

        String key = "follows:" + userId;
        //2. 判断是关注还是取关
        if (isFollow) {
            //2.1 关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            //将关注信息加入redis
            if(isSuccess) {
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            //2.2 取关，删除 delete from tb_follow where user_id = ? and follow_user_id = ?;
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
            if(isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        //3. 返回数据
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        //1. 获取登录用户
        Long userID = UserHolder.getUser().getId();

        //2. 查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Integer count = query().eq("user_id", userID).eq("follow_user_id", followUserId).count();

        //3. 判断
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        //1. 获取当前用户id
        Long userId = UserHolder.getUser().getId();
        String key1 = "follows:" + userId;
        String key2 = "follows:" + id;
        //2. 求交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        //无交集的情况
        if(intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //3.获得共同关注的id集合
        ArrayList<String> ids = new ArrayList<>(intersect);

        //4. 查询用户
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(users);
    }
}