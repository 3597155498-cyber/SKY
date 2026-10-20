package com.sky.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面跳转：让短地址 /ai 也能打开扫码点餐 H5，二维码内容更短
 * <p>
 * 静态资源由 WebMvcConfiguration 注册为 /ai/** -> classpath:/static/ai/
 */
@Controller
public class PageController {

    @GetMapping({"/ai", "/ai/"})
    public String aiPage() {
        return "redirect:/ai/index.html";
    }
}
