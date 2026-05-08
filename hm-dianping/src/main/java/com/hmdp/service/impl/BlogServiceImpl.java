package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result queryBlogById(Long id) {
        //查询Blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("数据不存在");
        }

        //查询用户
        setBlog(blog);
        isBlogLike(blog);
        return Result.ok(blog);

    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            setBlog(blog);
            isBlogLike(blog);
        });
        return Result.ok(records);
    }


    //是否点赞过
    private void isBlogLike(Blog blog) {
        //查询用户
        Long userid = UserHolder.getUser().getId();

        String key = "blog:liked:" + blog.getId();
        //判断用户是否点赞
        Boolean isLiked = stringRedisTemplate.opsForSet().isMember(key, userid.toString());

        //赋值给isLike
        blog.setIsLike(Boolean.TRUE.equals(isLiked));
    }

    //点赞
    @Override
    public Result likeBlog(Long id) {
        //查询用户
        Long userid = UserHolder.getUser().getId();

        String key = "blog:liked:" + id;
        //判断用户是否点赞
        Boolean isLiked = stringRedisTemplate.opsForSet().isMember(key, userid.toString());

        if (Boolean.FALSE.equals(isLiked)) {
            //如果未点赞，数据库点赞数+1
           boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
           if (isSuccess){
               stringRedisTemplate.opsForSet().add(key, userid.toString());
           }

        } else {
            //如果点赞了,取消点赞
            //数据库-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess){
                stringRedisTemplate.opsForSet().remove(key, userid.toString());
            }
        }
        return Result.ok();
    }


    //将用户昵称和头像封装到Blog中
    private void setBlog(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
