package com.sky.controller.user;

import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.entity.Orders;
import com.sky.result.Result;
import com.sky.service.OrderService;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderSubmitVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController("userOrderController")
@RequestMapping("/user/order")
@Api(tags = "用户端订单相关接口")
@Slf4j
public class OrderController {

    @Autowired
    private OrderService orderService;

    /**
     * 用户下单
     * @param ordersSubmitDTO
     * @return
     */
    @PostMapping("/submit")
    @ApiOperation("用户下单")
    public Result<OrderSubmitVO> submit(@RequestBody OrdersSubmitDTO ordersSubmitDTO) {
        log.info("用户下单：{}", ordersSubmitDTO);
        OrderSubmitVO orderSubmitVO = orderService.submitOrder(ordersSubmitDTO);
        return Result.success(orderSubmitVO);
    }

    /**
     * 订单支付
     * @param ordersPaymentDTO
     * @return
     */
    @PutMapping("/payment")
    @ApiOperation("订单支付")
    public Result<OrderPaymentVO> payment(@RequestBody OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        log.info("订单支付：{}", ordersPaymentDTO);
        OrderPaymentVO orderPaymentVO = orderService.payment(ordersPaymentDTO);
        return Result.success(orderPaymentVO);
    }

    /**
     * 模拟支付成功回调 - 手动调用
     */
    @GetMapping("/mockPaySuccess/{orderNumber}")
    @ApiOperation("模拟支付成功回调")
    public Result<String> mockPaySuccess(@PathVariable String orderNumber) {
        log.info("模拟支付成功回调，订单号：{}", orderNumber);
        orderService.mockPaySuccess(orderNumber);
        return Result.success("支付成功");
    }

    /**
     * 查询订单状态
     */
    @GetMapping("/status/{orderNumber}")
    @ApiOperation("查询订单状态")
    public Result<Integer> getOrderStatus(@PathVariable String orderNumber) {
        log.info("查询订单状态，订单号：{}", orderNumber);
        Orders orders = orderService.getByOrderNumber(orderNumber);
        if (orders == null) {
            return Result.error("订单不存在");
        }
        return Result.success(orders.getStatus());
    }
}