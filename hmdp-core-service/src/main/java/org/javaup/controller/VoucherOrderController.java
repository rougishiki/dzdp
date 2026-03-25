package org.javaup.controller;


import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.javaup.dto.CancelVoucherOrderDto;
import org.javaup.dto.GetVoucherOrderByVoucherIdDto;
import org.javaup.dto.GetVoucherOrderDto;
import org.javaup.dto.Result;
import org.javaup.execute.RateLimitHandler;
import org.javaup.ratelimit.extension.RateLimitScene;
import org.javaup.service.IReconciliationTaskService;
import org.javaup.service.ISeckillAccessTokenService;
import org.javaup.service.IVoucherOrderService;
import org.javaup.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ISeckillAccessTokenService accessTokenService;

    @Resource
    private RateLimitHandler rateLimitHandler;
    
    @Resource
    private IReconciliationTaskService reconciliationTaskService;

    /**
     * 发放秒杀令牌
     * 
     * @param voucherId 优惠券 ID
     * @return 秒杀访问令牌
     */
    @GetMapping("/seckill/token/{id}")
    public Result<String> issueSeckillAccessToken(@PathVariable("id") Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        rateLimitHandler.execute(voucherId, userId, RateLimitScene.ISSUE_TOKEN);
        String token = accessTokenService.issueAccessToken(voucherId, userId);
        return Result.ok(token);
    }

    /**
     * 执行秒杀下单
     * 
     * @param voucherId 优惠券 ID
     * @param accessToken 访问令牌（可选），用于防止重复提交和超卖
     * @return 订单 ID
     */
    @PostMapping("/seckill/{id}")
    public Result<Long> seckillVoucher(@PathVariable("id") Long voucherId,
                                       @RequestParam(name = "accessToken", required = false) String accessToken) {
        //1.获取用户 ID
        Long userId = UserHolder.getUser().getId();
        //2.使用令牌桶限流器对用户进行限流控制
        rateLimitHandler.execute(voucherId, userId, RateLimitScene.SECKILL_ORDER);
        //3.令牌校验
        if (accessTokenService.isEnabled()) {
            if (accessToken == null || !accessTokenService.validateAndConsume(voucherId, userId, accessToken)) {
                return Result.fail("令牌校验失败或令牌已失效");
            }
        }
        //4.执行秒杀
        return voucherOrderService.seckillVoucher(voucherId);
    }
    
    /**
     * 根据订单 ID 查询秒杀优惠券订单
     * 
     * @param getVoucherOrderDto 查询参数，包含订单 ID
     * @return 订单 ID
     */
    @PostMapping("/get/seckill/voucher/order-id")
    public Result<Long> getSeckillVoucherOrder(@Valid @RequestBody GetVoucherOrderDto getVoucherOrderDto) {
        return Result.ok(voucherOrderService.getSeckillVoucherOrder(getVoucherOrderDto));
    }
    
    /**
     * 根据优惠券 ID 查询秒杀优惠券订单 ID
     * 
     * @param getVoucherOrderByVoucherIdDto 查询参数，包含优惠券 ID
     * @return 订单 ID
     */
    @PostMapping("/get/seckill/voucher/order-id/by/voucher-id")
    public Result<Long> getSeckillVoucherOrderIdByVoucherId(@Valid @RequestBody GetVoucherOrderByVoucherIdDto getVoucherOrderByVoucherIdDto) {
        return Result.ok(voucherOrderService.getSeckillVoucherOrderIdByVoucherId(getVoucherOrderByVoucherIdDto));
    }
    
    /**
     * 取消优惠券订单
     * 
     * @param cancelVoucherOrderDto 取消订单参数，包含订单 ID
     * @return 是否取消成功
     */
    @PostMapping("/cancel")
    public Result<Boolean> cancel(@Valid @RequestBody CancelVoucherOrderDto cancelVoucherOrderDto) {
        return Result.ok(voucherOrderService.cancel(cancelVoucherOrderDto));
    }
    
    /**
     * 执行对账任务
     * 
     * @return 无返回值
     */
    @PostMapping(value = "/reconciliation/task/all")
    public Result<Void> reconciliationTaskAll() {
        reconciliationTaskService.reconciliationTaskExecute();
        return Result.ok();
    }
}
