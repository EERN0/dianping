package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 缓存数据-添加了逻辑过期时间（缓存击穿的解决方案）
 */
@Data
public class RedisData {
    private LocalDateTime expireTime;   // 逻辑过期时间
    private Object data;    // 缓存数据(各种类型的数据都可以存放在这)
}
