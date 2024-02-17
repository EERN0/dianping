package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    /**
     * 关注、取关
     *
     * @param followUserId 要关注/取关的用户id
     * @param isFollow     true-关注、false-取关
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 0.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 1.判断是关注，还是取关
        if (isFollow) {
            // 2.关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            save(follow);
        } else {
            // 3.取关，删除数据 delete from tb_flow where userId=? and follow_user_id=?
            remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
        }
        return Result.ok();
    }

    /**
     * 查询用户是否关注 followUserId
     *
     * @param followUserId 关注/取关的用户id
     */
    @Override
    public Result isFollow(Long followUserId) {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询是否关注 select count(*) from tb_follow where user_id=? and follow_user_id=?
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        // 3.判断是否关注     count > 0——>关注
        return Result.ok(count > 0);
    }
}
