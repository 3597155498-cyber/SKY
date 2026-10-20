package com.sky.ai.config;

import com.sky.ai.interceptor.AiUserContextInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * AI 服务只注册身份解析拦截器；不引入主服务的 JWT / Swagger / 静态资源配置。
 */
@Configuration
public class AiWebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private AiUserContextInterceptor aiUserContextInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(aiUserContextInterceptor).addPathPatterns("/ai/**");
    }
}
