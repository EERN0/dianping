package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * 服务实现类
 */
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
        // 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 判断当前用户是否已经点赞
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        // 设置blog的isLike字段
        blog.setIsLike(BooleanUtil.isTrue(isMember));
    }

    /**
     * blog点赞 --
     * 使用redis(key是前缀+博客id，value是点过赞的用户)
     *
     * @param blogId 博客id
     */
    @Override
    public Result likBlog(Long blogId) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.判断当前登录用户是否已经点赞
        String key = RedisConstants.BLOG_LIKED_KEY + blogId;
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if (BooleanUtil.isFalse(isMember)) {
            // 3.如果未点赞，可以点赞
            // 3.1 数据库点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", blogId).update();
            if (isSuccess) { // 更新mysql成功
                // 3.2 保存用户到Redis的set集合
                stringRedisTemplate.opsForSet().add(key, userId.toString());
            }
        } else {
            // 4.如果已点赞，取消点赞
            // 4.1 数据库点赞数-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", blogId).update();
            if (isSuccess) {
                // 4.2 把用户从redis的set集合中移除
                stringRedisTemplate.opsForSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
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
