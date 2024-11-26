package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    // lua脚本导入
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT =new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    // 阻塞队列
    public BlockingQueue<VoucherOrder> orderTasks=new LinkedBlockingQueue<VoucherOrder>(1024*1024);
    private static final ExecutorService SECKILL_ORDER = Executors.newSingleThreadExecutor();

    // 线程任务 使其在impl初始化后就运行
    @PostConstruct
    private void init(){
        SECKILL_ORDER.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while(true){
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();

        RLock lock = redissonClient.getLock("lock:order:" + userId);

        // 尝试获取锁 无参形式采用默认时间
        boolean tryLock = lock.tryLock();
        // 可以返回重试或直接返回错误信息
        if(!tryLock) {
            log.error("请勿重复下单");
            return;
        }
        // 业务完成后，手动释放锁
        try {
            proxy.createVoucherOrder(voucherOrder);
        }finally {
//            redisLock.unlock();
            lock.unlock();
        }
    }

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // lua
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        int res = result.intValue();
        if(result!=0) return Result.fail(res==1?"库存不足":"请勿重复下单噢");
//        long orderId = redisIdWorker.nextId("order");
        // 阻塞队列添加
        VoucherOrder voucherOrder = new VoucherOrder();
        // id create
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        orderTasks.add(voucherOrder);
        // 代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);
    }
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 查
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        // 开始否
        if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) return Result.fail("该活动未开始");
        // 结束否
        if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) return Result.fail("该活动已结束");
        // 库存否
        Long userId = UserHolder.getUser().getId();
        if(seckillVoucher.getStock()<1) return Result.fail("库存不足");
        // Redis实现分布式锁
//        SimpleRedisLock redisLock = new SimpleRedisLock("order" + userId, redisTemplate);
        // Redisson实现分布式锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        // 尝试获取锁 无参形式采用默认时间
        boolean tryLock = lock.tryLock();
        // 可以返回重试或直接返回错误信息
        if(!tryLock) return Result.fail("请勿重复下单！");
        // 业务完成后，手动释放锁
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally {
//            redisLock.unlock();
            lock.unlock();
        }
    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 一人一单
        Long userId = UserHolder.getUser().getId();

        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("请勿重复下单！");
            return ;
        }

        // 扣库存
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足");
            return;
        }

        // 创订单


        save(voucherOrder);
    }
}
