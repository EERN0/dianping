package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 关注、取关
     * --新增：把关注用户的id放入redis的set集合中, key-当前用户userId,  value-关注的用户followUserId
     *
     * @param followUserId 要关注/取关的用户id
     * @param isFollow     true-关注、false-取关
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 0.获取当前登录用户
        Long userId = UserHolder.getUser().getId();

        String key = "follows:" + userId;
        // 1.判断是关注，还是取关
        if (isFollow) {
            // 2.关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                // 把关注用户的id放入redis的set集合中   sadd userId followerUserId
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            // 3.取关，删除数据 delete from tb_flow where userId=? and follow_user_id=?
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if (isSuccess) {
                // 把关注用户的id从redis集合中移除 smove userId followerUserId
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
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
