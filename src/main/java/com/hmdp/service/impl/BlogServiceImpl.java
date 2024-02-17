package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 服务实现类
 */
@Slf4j
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查热点blog
     *
     * @param current
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();

        records.forEach(blog -> {
            // 查询blog相关用户
            this.queryBlogUser(blog);
            // 查询blog是否被当前用户点赞了，并设置blog对应成员
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 根据id查blog
     *
     * @param id blog的id
     */
    @Override
    public Result qeryBlogById(Long id) {
        // 1.查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("blog不存在!");
        }
        // 2.查询blog相关用户, 并设置blog的成员
        queryBlogUser(blog);
        // 3.查询blog是否被当前用户点赞了，并设置blog对应成员
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 查询blog是否被当前用户点赞了，并设置blog对应成员
     */
    private void isBlogLiked(Blog blog) {
        // 1.获取当前登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            log.info("用户未登录，无需查询是否点赞");
            return;
        }
        Long userId = user.getId();
        // 2.判断当前用户是否已经点赞
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());  // score不为空，就是用户点赞了
        // 3.设置blog的isLike字段
        blog.setIsLike(score != null);
    }

    /**
     * blog点赞 --
     * 使用redis(key是前缀+博客id，value是点过赞的用户)
     *
     * @param blogId 博客id
     */
    @Override
    public Result likeBlog(Long blogId) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.判断当前登录用户是否已经点赞
        String key = RedisConstants.BLOG_LIKED_KEY + blogId;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());  // 查sorted_set中userId对应的score（score是插入用户id的时间）
        if (score == null) {    // score为空，说明zset中没有该用户，即用户没点赞
            // 3.如果未点赞，可以点赞
            // 3.1 数据库点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", blogId).update();
            if (isSuccess) { // 更新mysql成功
                // 3.2 保存用户到Redis的zset集合    zadd key value score (这里的score是当前时间的毫秒值)
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 4.如果已点赞，取消点赞
            // 4.1 数据库点赞数-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", blogId).update();
            if (isSuccess) {
                // 4.2 把用户从redis的zset集合中移除
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询blog的点赞用户信息（前5个，按时间排序）
     *
     * @param id 博客id
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // 1.查询top5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);   // 拿出前5个key
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);      // 把list拼接为字符串
        // 3.根据用户id查询用户     WHERE id IN (5,1) ORDER BY FIELD(id, 5, 1)
        List<UserDTO> userDTOs = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 4.返回
        return Result.ok(userDTOs);
    }

    /**
     * 查询blog相关用户，封装用户部分消息
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        // blog显示用户昵称、头像
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
