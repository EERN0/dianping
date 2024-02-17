package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IFollowService extends IService<Follow> {
    /**
     * 关注、取关
     *
     * @param followUserId 要关注/取关的用户id
     * @param isFollow     true-关注、false-取关
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 查询是否关注
     *
     * @param followUserId 关注/取关的用户id
     */
    Result isFollow(Long followUserId);
}
