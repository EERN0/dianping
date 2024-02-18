package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IBlogService extends IService<Blog> {

    /**
     * 保存blog，并将笔记id推送给所有粉丝
     */
    Result saveBlog(Blog blog);

    Result queryHotBlog(Integer current);

    Result qeryBlogById(Long id);

    /**
     * blog点赞
     *
     * @param blogId
     * @return
     */
    Result likeBlog(Long blogId);

    /**
     * 查询blog的点赞用户信息（前5个，按时间排序）
     *
     * @param id 博客id
     * @return
     */
    Result queryBlogLikes(Long id);


    /**
     * 滚动分页查询收件箱
     *
     * @param max    当前时间戳 | 上一次查询结果的最小时间戳
     * @param offset 偏移量 第一次查询默认为0。非第一次查询 offset：在上一次的结果中，与最小值一样的元素的个数
     */
    Result queryBlogOfFollow(Long max, Integer offset);
}
