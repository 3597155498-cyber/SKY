package com.sky.service;

import com.sky.dto.UserLoginDTO;
import com.sky.entity.User;

public interface UserService {

    /**
     * 微信登录：code -> openid，用户不存在则自动注册
     *
     * @param userLoginDTO 登录参数
     * @return 用户信息
     */
    User wechatLogin(UserLoginDTO userLoginDTO);
}