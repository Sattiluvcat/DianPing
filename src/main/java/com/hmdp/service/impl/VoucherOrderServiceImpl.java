package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Override
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
        synchronized (userId.toString().intern()) {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 一人一单
        Long userId = UserHolder.getUser().getId();

            Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (count > 0) return Result.fail("请勿重复下单！");

            // 扣库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock=stock-1")
                    .eq("voucher_id", voucherId).gt("stock", 0)
                    .update();
            if (!success) return Result.fail("库存不足");

            // 创订单
            VoucherOrder voucherOrder = new VoucherOrder();
            // id create
            long order = redisIdWorker.nextId("order");
            voucherOrder.setId(order);

            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);

            save(voucherOrder);
            return Result.ok(voucherId);
    }
}
