package com.hmdp.dto;

import lombok.Data;

@Data
public class BlogLikeMessage {
    private Long blogId;
    private Long userId;
    private boolean isLiked;
}
