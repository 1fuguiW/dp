package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexPatterns;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;
import static com.hmdp.utils.RedisConstants.USER_SIGN_KEY;
import static com.hmdp.utils.SystemConstants.MAX_PAGE_SIZE;
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

        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }

        String code = RandomUtil.randomNumbers(6);

//        session.setAttribute("code", code);
                                        //key value   时间  单位
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code , LOGIN_CODE_TTL, TimeUnit.MINUTES);


        log.info("发送验证码成功，验证码：{}", code);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {


        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }

        //正确的code
//        Object cacheCode =  session.getAttribute("code");

        //从redis获取code
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        //提交的验证码
        String code = loginForm.getCode();
        //验证码校验,原始验证码可能过期，
        if(cacheCode == null || !cacheCode.equals(code))
        {
            return Result.fail("验证码错误或过期");
        }

        //根据手机号查询用户
        User user = query().eq("phone", phone).one();

        //判断用户是否存在
        if(user == null){
            //创建新用户
           user = createUserWithPhone(phone);
        }

        //保存用户
//        //将user转化为UserDto
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        //保存用户到Redis  用Token当key 要生成token 还要返回给客户端
        //TODO 生成Token
        String token = UUID.randomUUID().toString();
        //TODO 保存用户到Redis里
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //转化成userDto 然后putAll要map 就转化成map
        String tokenKey = "login:token:" + token;
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO , new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString())
        );


        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);

        //设置过期时间
        stringRedisTemplate.expire(tokenKey, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        return Result.ok(token);
    }

    //创建用户
    private User createUserWithPhone(String phone) {
        User user = new User();
        //手机号 名称
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }

    @Override
    public Result sign() {
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2. 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        // 3. 拼接key sign:userId:yyyyMM
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4. 获取今天是本月第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5. 写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2. 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        // 3. 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4. 获取今天是本月第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5. 获取本月截止今天为止的所有签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6. 循环遍历
        int count = 0;
        while (true) {
            // 6.1 让这个数字与1做与运算，得到数字的最后一个bit位
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                break;
            } else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }




}
