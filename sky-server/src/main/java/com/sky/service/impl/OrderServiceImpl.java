package com.sky.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.entity.*;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.*;
import com.sky.service.OrderService;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderSubmitVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private WeChatPayUtil weChatPayUtil;
    @Autowired
    private UserMapper userMapper;

    @Override
    @Transactional
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {
        AddressBook addressBook = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if (addressBook == null) {
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }
        Long UserId= BaseContext.getCurrentId();
        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(UserId);
        List<ShoppingCart> list= shoppingCartMapper.list(shoppingCart);
        if (list == null || list.size() == 0) {
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }

        Orders orders = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO, orders);
        orders.setOrderTime(LocalDateTime.now());
        orders.setPayStatus(Orders.UN_PAID);
        orders.setStatus(Orders.PENDING_PAYMENT);
        orders.setNumber(String.valueOf(System.currentTimeMillis()));
        orders.setPhone(addressBook.getPhone());
        orders.setConsignee(addressBook.getConsignee());
        orders.setUserId(UserId);
        orderMapper.insert(orders);
        log.info("订单插入成功，订单ID：{}", orders.getId());

        List<OrderDetail> orderDetails = new ArrayList<>();
        for(ShoppingCart cart:list){
            OrderDetail  orderDetail = new OrderDetail();
            BeanUtils.copyProperties(cart,orderDetail);
            orderDetail.setOrderId(orders.getId());
            orderDetails.add(orderDetail);
        }
        orderDetailMapper.insertBatch(orderDetails);
        shoppingCartMapper.deleteByUserId(UserId);
        OrderSubmitVO orderSubmitVO = OrderSubmitVO.builder()
                .id(orders.getId())
                .orderTime(orders.getOrderTime())
                .orderNumber(orders.getNumber())
                .orderAmount(orders.getAmount())
                .build();
        return orderSubmitVO;
    }

    /**
     * 订单支付 - 模拟支付
     */
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        log.info("模拟支付，订单号：{}", ordersPaymentDTO.getOrderNumber());

        // 获取当前用户
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.getById(userId);

        // 模拟微信支付返回的数据格式
        String prepayId = "wx" + System.currentTimeMillis();

        // 构造返回给前端的支付参数
        OrderPaymentVO vo = OrderPaymentVO.builder()
                .timeStamp(String.valueOf(System.currentTimeMillis() / 1000))
                .nonceStr("mock_nonce_str_" + System.currentTimeMillis())
                .packageStr("prepay_id=" + prepayId)
                .signType("MD5")
                .paySign("mock_pay_sign_" + System.currentTimeMillis())
                .build();

        log.info("模拟支付返回数据：{}", vo);

        // 自动回调（延迟3秒）
        new Thread(() -> {
            try {
                Thread.sleep(3000);
                log.info("自动支付回调，订单号：{}", ordersPaymentDTO.getOrderNumber());
                paySuccess(ordersPaymentDTO.getOrderNumber());
            } catch (InterruptedException e) {
                log.error("自动支付回调失败", e);
            }
        }).start();

        return vo;
    }

    /**
     * 模拟支付回调接口 - 供前端手动调用
     */
    @Override
    public void mockPaySuccess(String orderNumber) {
        log.info("模拟支付回调，订单号：{}", orderNumber);
        paySuccess(orderNumber);
    }

    @Override
    public Orders getByOrderNumber(String orderNumber) {
        return orderMapper.getByNumber(orderNumber);
    }

    /**
     * 支付成功，修改订单状态
     *
     * @param outTradeNo
     */
    public void paySuccess(String outTradeNo) {
        log.info("支付成功回调，订单号：{}", outTradeNo);

        // 根据订单号查询订单
        Orders ordersDB = orderMapper.getByNumber(outTradeNo);

        if (ordersDB == null) {
            log.error("订单不存在，订单号：{}", outTradeNo);
            throw new OrderBusinessException("订单不存在");
        }

        // 检查订单是否已支付
        if (ordersDB.getPayStatus() == Orders.PAID) {
            log.info("订单已支付，订单号：{}", outTradeNo);
            return;
        }

        // 根据订单id更新订单的状态、支付方式、支付状态、结账时间
        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.TO_BE_CONFIRMED)  // 待接单状态
                .payStatus(Orders.PAID)           // 已支付
                .checkoutTime(LocalDateTime.now()) // 结账时间
                .build();

        orderMapper.update(orders);
        log.info("订单状态更新成功，订单ID：{}，状态：待接单，支付状态：已支付", ordersDB.getId());
    }
}