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


}
