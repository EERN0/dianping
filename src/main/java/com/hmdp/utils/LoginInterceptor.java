package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor {

    /**
     * Controller方法处理之前，进行拦截
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.获取session
        HttpSession session = request.getSession();
        // 2.获取session中的用户
        Object userDTO = session.getAttribute("user");
        // 3.判断用户是否存在
        if (userDTO == null) {
            // 4.不存在，拦截，返回http 401(unauthorized缺乏身份验证)状态码
            response.setStatus(401);
            return false;
        }
        // 5.用户存在，保存用户信息到 ThreadLocal
        // ThreadLocal是一个线程内部的存储类，可以在指定线程内存储数据，数据存储以后，只有指定线程可以得到存储数据。
        // 一次http请求对应一个线程，所以可以用ThreadLocal来存储用户信息
        UserHolder.saveUser((UserDTO) userDTO);

        // 6.放行
        return true;
    }

    /**
     * 视图渲染完成之后执行afterCompletion，用于清理资源，防止内存泄漏
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户，释放ThreadLocal中的资源
        UserHolder.removeUser();
    }
}
