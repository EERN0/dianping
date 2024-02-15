package com.hmdp;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import javax.annotation.Resource;
import java.io.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * 登录数据库的1000多个用户，把redis中的token另存为txt文件
 */
@SpringBootTest
@AutoConfigureMockMvc
public class CreateLoginTokenTest {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Test
    public void createToken() throws IOException {
        List<User> userList = userService.list();
        PrintWriter printWriter = new PrintWriter(new FileWriter("E:\\token.txt"));
        for (User user : userList) {
            // 生成随机token，作为登录令牌，保存到redis中
            String token = UUID.randomUUID().toString(true);
            // 将UserDTO对象转为hash存储 (field:tokenKey, value:userDTO)
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                    CopyOptions.create()
                            .setIgnoreNullValue(true)
                            .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
            // 用户登录的token存入redis
            String tokenKey = LOGIN_USER_KEY + token;
            stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
            stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES); // 设置过期时间

            // 把token写入txt
            printWriter.print(token + "\n");
            printWriter.flush();
        }
    }

}