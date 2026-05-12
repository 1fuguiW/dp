package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;


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
        UserDTO user = UserHolder.getUser();

        if (user == null){
            return;
        }
        Long userid = user.getId();

        String key = "blog:liked:" + blog.getId();
        //判断用户是否点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userid.toString());

        //赋值给isLike
        blog.setIsLike(score != null);
    }

    //点赞
    @Override
    public Result likeBlog(Long id) {
        //查询用户
        Long userid = UserHolder.getUser().getId();

        String key = "blog:liked:" + id;
        //判断用户是否点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userid.toString());

        if (score == null) {
            //如果未点赞，数据库点赞数+1
           boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
           if (isSuccess){
               stringRedisTemplate.opsForZSet().add(key, userid.toString(),System.currentTimeMillis());
           }

        } else {
            //如果点赞了,取消点赞
            //数据库-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess){
                stringRedisTemplate.opsForZSet().remove(key, userid.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //1.查询top5的点赞用户
        String key=BLOG_LIKED_KEY+id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5==null||top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //2.解析出其中的id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",",ids);
        //3.根据用户id查询用户
        //SELECT *from tb_user where id IN(5,1) ORDER BY FIELD(id,5,1)
        List<UserDTO> userDTOS = userService.query()
                .in("id",ids).last("order by field(id,"+idStr+")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        //4.返回
        return Result.ok(userDTOS);
    }


    //将用户昵称和头像封装到Blog中
    private void setBlog(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        save(blog);
        // 查询所有粉丝 select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query()
                .eq("follow_user_id", user.getId()).list();
        // 推送博客id到所有粉丝的收件箱
        for (Follow follow : follows) {
            Long userId = follow.getUserId();
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY + userId;

        // 滚动分页查询收件箱 ZREVRANGEBYSCORE key max min LIMIT offset count
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);

        // 非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }

        // 解析数据：blogId、minTime（最小时间戳）、offset（与最小值相同的个数）
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            // 获取blogId
            ids.add(Long.valueOf(tuple.getValue()));
            // 获取分数（时间戳）
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }

        // 根据id查询blog（保持顺序）
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Blog blog : blogs) {
            // 查询用户信息
            setBlog(blog);
            // 查询是否点赞
            isBlogLike(blog);
        }

        // 封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setMinTime(minTime);
        r.setOffset(os);
        return Result.ok(r);
    }
}
