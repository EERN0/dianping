package com.hmdp.consumer;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.dto.BlogLikeMessage;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.RedisConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
@RequiredArgsConstructor
public class BlogLikeConsumer {

    @Autowired
    private IBlogService blogService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 异步处理点赞消息 -- 点赞
     * @param blogLikeMessage
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "liked.queue", durable = "true"),
            exchange = @Exchange(name = "liked.direct"),
            key = "liked"   // 点赞的 routing key
    ))
    public void blogLikeConsumer(BlogLikeMessage blogLikeMessage) {
        Long blogId = blogLikeMessage.getBlogId();
        Long userId = blogLikeMessage.getUserId();

        // 构建mp更新条件
        UpdateWrapper<Blog> updateWrapper = new UpdateWrapper<>();
        updateWrapper.setSql("liked = liked + 1").eq("id", blogId);
        boolean isSuccess = blogService.update(updateWrapper);

        if (isSuccess) { // 更新mysql成功
            // 保存点赞用户到Redis的zset集合    zadd key value score (这里的score是当前时间的毫秒值)
            stringRedisTemplate.opsForZSet().add(RedisConstants.BLOG_LIKED_KEY + blogId, userId.toString(), System.currentTimeMillis());
            log.info("点赞成功...");
        }
    }

    /**
     * 异步处理点赞消息 -- 取消点赞
     * @param blogLikeMessage
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "liked.queue", durable = "true"),
            exchange = @Exchange(name = "liked.direct"),
            key = "unlike"  // 取消点赞的 routing key
    ))
    public void blogUnLikeConsumer(BlogLikeMessage blogLikeMessage) {
        Long blogId = blogLikeMessage.getBlogId();
        Long userId = blogLikeMessage.getUserId();

        // 构建mp更新条件
        UpdateWrapper<Blog> updateWrapper = new UpdateWrapper<>();
        updateWrapper.setSql("liked = liked - 1").eq("id", blogId);
        boolean isSuccess = blogService.update(updateWrapper);

        if (isSuccess) {
            // blogId-userId，移除zset对应的数据
            stringRedisTemplate.opsForZSet().remove(RedisConstants.BLOG_LIKED_KEY + blogId, userId.toString());
            log.info("取消点赞成功...");
        }
    }
}
