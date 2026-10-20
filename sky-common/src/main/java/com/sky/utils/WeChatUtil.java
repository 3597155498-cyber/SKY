package com.sky.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sky.constant.MessageConstant;
import com.sky.exception.LoginFailedException;
import com.sky.properties.WeChatProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * 微信登录工具类
 * 生产环境调用微信 code2session 接口换取 openid；
 * 本地开发可开启 sky.wechat.mock=true，用 code 派生稳定的 mock openid，
 * 避免强依赖 appid/secret 与公网调用。
 */
@Component
@Slf4j
public class WeChatUtil {

    private static final String LOGIN_URL = "https://api.weixin.qq.com/sns/jscode2session";

    private final WeChatProperties weChatProperties;

    public WeChatUtil(WeChatProperties weChatProperties) {
        this.weChatProperties = weChatProperties;
    }

    /**
     * 根据登录 code 获取 openid
     */
    public String getOpenid(String code) {
        if (code == null || code.trim().isEmpty()) {
            throw new LoginFailedException(MessageConstant.LOGIN_FAILED);
        }
        if (weChatProperties.isMock()) {
            String openid = mockOpenid(code);
            log.info("mock 模式登录, code={}, openid={}", code, openid);
            return openid;
        }
        Map<String, String> paramMap = new HashMap<>();
        paramMap.put("appid", weChatProperties.getAppid());
        paramMap.put("secret", weChatProperties.getSecret());
        paramMap.put("js_code", code);
        paramMap.put("grant_type", "authorization_code");
        String json = HttpClientUtil.doGet(LOGIN_URL, paramMap);
        JSONObject jsonObject = JSON.parseObject(json);
        String openid = jsonObject == null ? null : jsonObject.getString("openid");
        if (openid == null) {
            log.warn("微信登录失败, code={}, 响应={}", code, json);
            throw new LoginFailedException(MessageConstant.LOGIN_FAILED);
        }
        return openid;
    }

    private String mockOpenid(String code) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(code.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("mock_");
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new LoginFailedException(MessageConstant.LOGIN_FAILED);
        }
    }
}