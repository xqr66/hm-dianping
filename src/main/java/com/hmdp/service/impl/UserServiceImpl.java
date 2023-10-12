package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.experimental.FieldNameConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;


import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }

        //3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        //4.保存验证码到session
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
//        session.setAttribute("code", code);
//        session.setAttribute("phone", phone);
        //5.发送验证码
        log.debug("发送短信验证码成功,验证码:{}", code);
        //返回ok

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        String cachePhone = (String) session.getAttribute("phone");

        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }
        //2.从redis获取验证码并校验
        Object cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();

        if(cacheCode == null || !cacheCode.equals(code)) {
            //3.不一致，报错
            return Result.fail("验证码错误");
        }

        //4.一致，根据手机号查询用户 select * from tb_user where phone = ?
        User user = query().eq("phone",phone).one();


        //5.判断用户是否存在
        if(user == null) {
            //6.不存在，创建用户并保存
            user = createUserWithPhone(phone);
        }
        //7.保存用户信息到Session中
        //7.1随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);//simple表示不带下划线

        //7.2 将User对象转为Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        //7.3存储
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                                    .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        //7.4设置token的有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);

        //8. 返回token
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //1. 获取登录用户
        Long userId = UserHolder.getUser().getId();
        //2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        //3 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4. 获取今天是第几天
        int dayOfMonth = now.getDayOfMonth();
        //5. 写入Redis select key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2. 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        //3. 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4. 获取今天是第几天
        int dayOfMonth = now.getDayOfMonth();
        //5. 获取签到的bitMap,返回的是一个十进制数字
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0));
        if(result == null || result.isEmpty()) {
            //没有取到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num == null || num == 0) {
            return Result.ok(0);
        }
        //6. 循环判断签到天数
        int count = 0;
        while(true) {
            if((num & 1) == 0) {
                break;
            } else{
                num >>>= 1;
                count ++;
            }
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //2.保存用户
        save(user);
        return user;
    }
}
