package com.sky.service.impl;

import com.sky.dto.UserLoginDTO;
import com.sky.entity.User;
import com.sky.mapper.UserMapper;
import com.sky.service.UserService;
import com.sky.utils.WeChatUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@Slf4j
public class UserServiceImpl implements UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private WeChatUtil weChatUtil;

    @Override
    public User wechatLogin(UserLoginDTO userLoginDTO) {
        String openid = weChatUtil.getOpenid(userLoginDTO.getCode());
        User user = userMapper.getByOpenid(openid);
        if (user != null) {
            log.info("老用户登录, userId={}", user.getId());
            return user;
        }
        String suffix = openid.length() > 6 ? openid.substring(openid.length() - 6) : openid;
        user = User.builder()
                .openid(openid)
                .name("微信用户_" + suffix)
                .createTime(LocalDateTime.now())
                .build();
        userMapper.insert(user);
        log.info("新用户注册成功, userId={}", user.getId());
        return user;
    }
}