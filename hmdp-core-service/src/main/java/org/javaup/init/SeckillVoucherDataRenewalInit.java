package org.javaup.init;

import cn.hutool.core.date.LocalDateTimeUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.entity.SeckillVoucher;
import org.javaup.service.ISeckillVoucherService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 秒杀优惠券数据重置-库存恢复
 * @author: 阿星不是程序员
 *
 * 说明：本类仅用于开发/测试环境，用于恢复秒杀券库存。
 * 注意：已移除自动延长优惠券时间的功能，避免修改业务数据。
 **/
@Slf4j
@Order(2)
@Component
public class SeckillVoucherDataRenewalInit {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @PostConstruct
    public void init(){
        // 自动延长优惠券时间的功能已移除，避免意外修改业务数据
        // 如需恢复库存功能，可取消下面注释
        // renewalStock();
    }

    /**
     * 恢复秒杀券库存
     * 将已售出的库存恢复到初始值，用于测试环境重复测试
     */
    public void renewalStock(){
        log.info("==========将优惠券的库存数量恢复==========");
        List<SeckillVoucher> seckillVoucherList = seckillVoucherService.list();
        for (SeckillVoucher seckillVoucher : seckillVoucherList) {
            if (!seckillVoucher.getInitStock().equals(seckillVoucher.getStock())) {
                seckillVoucherService.lambdaUpdate()
                        .set(SeckillVoucher::getStock,seckillVoucher.getInitStock())
                        .set(SeckillVoucher::getUpdateTime,LocalDateTimeUtil.now())
                        .eq(SeckillVoucher::getId,seckillVoucher.getId())
                        .eq(SeckillVoucher::getVoucherId,seckillVoucher.getVoucherId())
                        .update();
                log.info("恢复库存成功，voucherId={}，stock={}", seckillVoucher.getVoucherId(), seckillVoucher.getInitStock());
            }
        }
    }
}
