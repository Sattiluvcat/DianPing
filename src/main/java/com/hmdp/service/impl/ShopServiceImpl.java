package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    // TODO 封装缓存工具类（击穿 穿透啥的）
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    // 互斥锁deal with缓存击穿
    @Override
    public Result queryById(Long id) {
        // 缓存击穿
//        Shop shop=queryWithMutex(id);
        // 逻辑过期
        Shop shop=queryWithLogicalExpire(id);
        if(shop==null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    // 原查询方法 缓存击穿解决 逻辑过期 不需要考虑缓存穿透
    public Shop queryWithLogicalExpire(Long id) {
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        // 未命中直接返回
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        // 命中 反序列化 检查有效期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        // 原为Object类型 一组json 用json承接
        Shop shop=JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 有效 直接返回
        if(expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }
        // 无效 获取互斥锁
        String keyLock=RedisConstants.LOCK_SHOP_KEY + id;
        boolean lock = getLock(keyLock);
        // 获取成功 创建新线程进行重建
        if(lock){
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try{// 重建缓存
                    this.save2Redis(id, 20L);
                }catch(Exception e){
                    throw new RuntimeException(e);
                }finally{
                    unLock(keyLock);
                }
            });
        }
        // 获取失败
        // 无论获取状态是啥 返回过期的信息
        return shop;
    }


    // 原查询方法 缓存击穿解决 互斥锁法
    public Shop queryWithMutex(Long id) {
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if(shopJson!=null) return null;
        // 未命中 进行缓存重建
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop=null;
        try {
            boolean lock = getLock(lockKey);
            if (!lock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 获取锁成功
            // 检查Redis此时是否存在——DoubleCheck
            if (StrUtil.isNotBlank(shopJson)) {
                unLock(lockKey);
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            if (shopJson != null) {
                unLock(lockKey);
                return null;
            }
            // 依然不存在则重建
            shop = getById(id);
            Thread.sleep(200);
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            }
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }catch (Exception e){
            throw new RuntimeException(e);
        }finally {unLock(lockKey);}
        return shop;
    }


    // 原查询方法 缓存穿透解决
    public Shop queryWithGetThrough(Long id) {
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if(shopJson!=null) return null;
        Shop shop = getById(id);
        if(shop==null) {
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    public void save2Redis(Long id,Long expireSeconds) throws InterruptedException {
        Shop shop = getById(id);
        Thread.sleep(200L);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 创建互斥锁
     */
    private boolean getLock(String key){
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(b);
    }
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id=shop.getId();
        if(id==null) return Result.fail("商铺id不能为空");
        updateById(shop);
        // 删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
