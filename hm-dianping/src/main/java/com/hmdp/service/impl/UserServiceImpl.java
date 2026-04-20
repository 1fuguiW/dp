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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;
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




}
