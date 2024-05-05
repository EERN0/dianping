package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.BlogLikeMessage;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * 服务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

    private final RabbitTemplate rabbitTemplate;

    /**
     * 保存blog，并将笔记id推送给所有粉丝
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO userDTO = UserHolder.getUser();
        blog.setUserId(userDTO.getId());
        // 2.保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增blog失败!");
        }
        // 3.查询blog作者的所有粉丝 select * from tb_follow where follow_user_id = 当前用户id
        List<Follow> follows = followService.query().eq("follow_user_id", userDTO.getId()).list();
        // 4.推送笔记id给所有粉丝
        for (Follow follow : follows) {
            // 4.1 获取粉丝id
            Long userId = follow.getUserId();
            // 4.2 把blogId推送给userId
            String key = FEED_KEY + userId;
            //public Boolean add(K key, V value, double score)
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
            // sorted_set中key是userId, value是blogId，score是时间戳
        }
        // 3.返回blogId
        return Result.ok(blog.getId());
    }

    /**
     * 滚动分页查询收件箱（blog推送到粉丝收件箱——redis）
     * <p>
     * redis中存的feed流blog，key——feed:userId, value——blogId, score——时间戳
     * </p>
     *
     * @param max    当前时间戳（第1次） || 上一次查询结果的最小时间戳（非第1次）
     * @param offset 偏移量 第一次查询默认为0；非第一次查询 offset：在上一次查询的结果中，与最小值一样的元素的个数
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();

        // 2.查询收件箱（滚动分页查询）  ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = FEED_KEY + userId;
        // Set<ZSetOperations.TypedTuple<V>> reverseRangeByScoreWithScores(K key, double min, double max, long offset, long count)
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }

        // 3.解析数据：blogId、minTime（时间戳）、offset（偏移量）
        List<Long> blogIds = new ArrayList<>(typedTuples.size());
        long minTime = 0;   // 上一次查询结果的最小时间戳
        int cnt = 1;        // 计算offset偏移量的

        // 4.根据id查询blog
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            // 4.1 获取博客id
            blogIds.add(Long.valueOf(tuple.getValue()));
            // 4.2 获取score
            long time = tuple.getScore().longValue();
            if (time == minTime) {  // 当前time和上一次的相同，cnt累加
                cnt++;
            } else {
                minTime = time;
                cnt = 1;
            }
        }
        // 5.根据id查询blog,为了保证顺序, 使用 WHERE id IN (5,1) ORDER BY FIELD(id, 5, 1)
        String blogIdsStr = StrUtil.join(",", blogIds);
        List<Blog> blogs = query().in("id", blogIds).last("ORDER BY FIELD(id," + blogIdsStr + ")").list();

        // 6.处理每个blog--点赞数
        for (Blog blog : blogs) {
            // 6.1 查询blog相关用户
            queryBlogUser(blog);
            // 6.2 查询blog是否被当前用户点赞了，并设置blog对应成员变量
            isBlogLiked(blog);
        }
        // 7.封装并返回ScrollResult对象
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(cnt);
        r.setMinTime(minTime);

        return Result.ok(r);
    }

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
        //if (score == null) {    // score为空，说明zset中没有该用户，即用户没点赞
        //    // 3.如果未点赞，可以点赞
        //    // 3.1 数据库点赞数+1
        //    boolean isSuccess = update().setSql("liked = liked + 1").eq("id", blogId).update();
        //    if (isSuccess) { // 更新mysql成功
        //        // 3.2 保存用户到Redis的zset集合    zadd key value score (这里的score是当前时间的毫秒值)
        //        stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
        //    }
        //} else {
        //    // 4.如果已点赞，取消点赞
        //    // 4.1 数据库点赞数-1
        //    boolean isSuccess = update().setSql("liked = liked - 1").eq("id", blogId).update();
        //    if (isSuccess) {
        //        // 4.2 把用户从redis的zset集合中移除
        //        stringRedisTemplate.opsForZSet().remove(key, userId.toString());
        //    }
        //}

        // 把点赞消息发送到 RabbitMQ，实现消息的异步解耦
        BlogLikeMessage blogLikeMessage = new BlogLikeMessage();
        blogLikeMessage.setBlogId(blogId);
        blogLikeMessage.setUserId(userId);
        if (score == null) {
            // 之前没点赞，把点赞消息发送到RabbitMQ convertAndSend(交换机, route-key, 消息)
            try {
                rabbitTemplate.convertAndSend("liked.direct", "liked", blogLikeMessage);
            } catch (Exception e) {
                log.error("发送点赞消息失败{}", e.getMessage());
            }
        } else {
            // 之前点过赞了，现在取消点赞 (同一个消息队列，跳过routingKey区分消息类型)
            try{
                rabbitTemplate.convertAndSend("liked.direct", "unlike", blogLikeMessage);
            }catch (Exception e){
                log.error("发送取消点赞消息失败{}", e.getMessage());
            }
            //System.out.println("之前已经点过赞了，score:" + score + "，现在取消点赞");
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
        // 3.根据用户id查询用户     按条件顺序排序: WHERE id IN (5,1) ORDER BY FIELD(id, 5, 1)
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
