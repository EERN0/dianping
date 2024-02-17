package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

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
                // 把关注用户的id放入redis的set集合中   sadd userId followUserId
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            // 3.取关，删除数据 delete from tb_flow where userId=? and follow_user_id=?
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if (isSuccess) {
                // 把关注用户的id从redis集合中移除 smove userId followUserId
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

    /**
     * 查询共同关注 （当前用户 与 指定用户）
     *
     * @param id 指定用户
     */
    @Override
    public Result followCommons(Long id) {
        // 1.获取当前用户的key
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        // 2.获取指定用户的key2
        String key2 = "follows:" + id;
        // 3.求当前用户和指定用户的交集  sinter s1 s2
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);

        if (intersect == null || intersect.isEmpty()) {
            // 两集合无交集
            return Result.ok(Collections.emptyList());
        }
        // 4.解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 5.查询用户
        List<UserDTO> userDTOS = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }
}
