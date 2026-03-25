package org.javaup.controller;


import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.javaup.dto.DelayVoucherReminderDto;
import org.javaup.dto.GetSeckillVoucherDto;
import org.javaup.dto.Result;
import org.javaup.dto.SeckillVoucherDto;
import org.javaup.dto.UpdateSeckillVoucherDto;
import org.javaup.dto.UpdateSeckillVoucherStockDto;
import org.javaup.dto.VoucherDto;
import org.javaup.dto.VoucherSubscribeBatchDto;
import org.javaup.dto.VoucherSubscribeDto;
import org.javaup.entity.Voucher;
import org.javaup.model.SeckillVoucherFullModel;
import org.javaup.service.ISeckillVoucherService;
import org.javaup.service.IVoucherService;
import org.javaup.vo.GetSubscribeStatusVo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/voucher")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;
    
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    
    /**
     * 根据优惠券 ID 查询秒杀优惠券详情
     * 
     * @param getSeckillVoucherDto 查询参数，包含优惠券 ID
     * @return 秒杀优惠券完整信息，包括库存、时间限制等
     */
    @PostMapping("/get")
    public Result<SeckillVoucherFullModel> get(@Valid @RequestBody GetSeckillVoucherDto getSeckillVoucherDto) {
        // 根据优惠券 ID 查询秒杀优惠券详情（包含完整信息）
        return Result.ok(seckillVoucherService.queryByVoucherId(getSeckillVoucherDto.getVoucherId()));
    }
    /**
     * 创建普通优惠券（非秒杀券）
     * 
     * @param voucherDto 优惠券创建参数，包括店铺 ID、优惠规则等
     * @return 新创建的优惠券 ID
     */
    @PostMapping
    public Result<Long> addVoucher(@Valid @RequestBody VoucherDto voucherDto) {
        // 创建普通优惠券（非秒杀券），添加到布隆过滤器防止穿透
        final Long voucherId = voucherService.addVoucher(voucherDto);
        return Result.ok(voucherId);
    }
    /**
     * 创建秒杀优惠券
     * 
     * @param seckillVoucherDto 秒杀优惠券创建参数，包括库存、秒杀时间、价格等
     * @return 新创建的秒杀优惠券 ID
     */
    @PostMapping("/seckill")
    public Result<Long> addSeckillVoucher(@Valid @RequestBody SeckillVoucherDto seckillVoucherDto) {
        // 创建新的优惠券记录，返回优惠券 ID
        final Long voucherId = voucherService.addSeckillVoucher(seckillVoucherDto);
        return Result.ok(voucherId);
    }

    /**
     * 更新秒杀优惠券信息
     * 
     * @param updateSeckillVoucherDto 更新参数，包括优惠券 ID、时间限制、人群限制等
     * @return 无返回值
     */
    @PostMapping("/update/seckill")
    public Result<Void> updateSeckillVoucher(@Valid @RequestBody UpdateSeckillVoucherDto updateSeckillVoucherDto) {
        // 更新秒杀优惠券信息（包括时间、人群限制等），并发送缓存失效通知
        voucherService.updateSeckillVoucher(updateSeckillVoucherDto);
        return Result.ok();
    }
    
    /**
     * 更新秒杀优惠券库存
     * 
     * @param updateSeckillVoucherDto 库存更新参数，包括优惠券 ID 和库存变化量
     * @return 无返回值
     */
    @PostMapping("/update/seckill/stock")
    public Result<Void> updateSeckillVoucherStock(@Valid @RequestBody UpdateSeckillVoucherStockDto updateSeckillVoucherDto) {
        // 修改秒杀优惠券库存（支持增减），同步更新 Redis 库存，库存增加时自动触发订阅队列发券
        voucherService.updateSeckillVoucherStock(updateSeckillVoucherDto);
        return Result.ok();
    }

    /**
     * 查询指定店铺的所有优惠券列表
     * 
     * @param shopId 店铺 ID
     * @return 该店铺的优惠券列表
     */
    @GetMapping("/list/{shopId}")
    public Result<List<Voucher>> queryVoucherOfShop(@PathVariable("shopId") Long shopId) {
       // 查询指定店铺的所有优惠券列表
       return voucherService.queryVoucherOfShop(shopId);
    }
    
    /**
     * 用户订阅秒杀活动
     * 
     * @param voucherSubscribeDto 订阅参数，包括用户 ID 和优惠券 ID
     * @return 无返回值
     */
    @PostMapping("/subscribe")
    public Result<Void> subscribe(@Valid @RequestBody VoucherSubscribeDto voucherSubscribeDto){
        // 用户订阅秒杀活动（加入订阅 ZSET），开售时自动通知
        voucherService.subscribe(voucherSubscribeDto);
        return Result.ok();
    }
    
    /**
     * 取消订阅秒杀活动
     * 
     * @param voucherSubscribeDto 取消订阅参数，包括用户 ID 和优惠券 ID
     * @return 无返回值
     */
    @PostMapping("/unsubscribe")
    public Result<Void> unsubscribe(@Valid @RequestBody VoucherSubscribeDto voucherSubscribeDto){
        // 取消订阅秒杀活动
        voucherService.unsubscribe(voucherSubscribeDto);
        return Result.ok();
    }
    
    /**
     * 查询用户对指定优惠券的订阅状态
     * 
     * @param voucherSubscribeDto 查询参数，包括用户 ID 和优惠券 ID
     * @return 订阅状态：0=未订阅，1=已订阅
     */
    @PostMapping("/get/subscribe/status")
    public Result<Integer> getSubscribeStatus(@Valid @RequestBody VoucherSubscribeDto voucherSubscribeDto){
        // 查询用户对指定优惠券的订阅状态（0=未订阅，1=已订阅）
        return Result.ok(voucherService.getSubscribeStatus(voucherSubscribeDto));
    }
    
    /**
     * 批量查询用户对多个优惠券的订阅状态
     * 
     * @param voucherSubscribeBatchDto 批量查询参数，包括用户 ID 和多个优惠券 ID
     * @return 订阅状态列表，每个状态包含优惠券 ID 和订阅状态
     */
    @PostMapping("/get/subscribe/status/batch")
    public Result<List<GetSubscribeStatusVo>> getSubscribeStatusBatch(@Valid @RequestBody VoucherSubscribeBatchDto voucherSubscribeBatchDto){
        // 批量查询用户对多个优惠券的订阅状态
        return Result.ok(voucherService.getSubscribeStatusBatch(voucherSubscribeBatchDto));
    }
    
    /**
     * 发送延迟提醒
     * 
     * @param delayVoucherReminderDto 延迟提醒参数，包括用户 ID、优惠券 ID 和提醒时间
     * @return 无返回值
     */
    @PostMapping("/delay/voucher/reminder")
    public Result<Void> delayVoucherReminder(@Valid @RequestBody DelayVoucherReminderDto delayVoucherReminderDto){
        // 发送延迟提醒（基于 RocketMQ 延迟队列），在开售前指定时间提醒用户
        voucherService.delayVoucherReminder(delayVoucherReminderDto);
        return Result.ok();
    }
}
