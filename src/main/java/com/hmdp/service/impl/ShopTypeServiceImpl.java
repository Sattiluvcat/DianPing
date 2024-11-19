package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> queryOrderByAsc() {
        Set<String> shopTypeSet = stringRedisTemplate.opsForZSet().range("cache:shopType:", 0, -1);
        if (shopTypeSet != null && !shopTypeSet.isEmpty()) {
            log.info("从缓存中获取商铺类型列表···");
            return shopTypeSet.stream()
                    .map(json -> BeanUtil.copyProperties(JSONUtil.toBean(json, ShopType.class), ShopType.class))
                    .collect(Collectors.toList());
        }
        List<ShopType> typeList = query().orderByAsc("sort").list();
        typeList.forEach(type -> stringRedisTemplate.opsForZSet().add("cache:shopType:", JSONUtil.toJsonStr(type), type.getSort()));
        return typeList;
    }
}
