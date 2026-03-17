package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
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
import com.sky.websocket.WebSocketServer;
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
    @Autowired
    private WebSocketServer  webSocketServer;

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


        Orders existingOrder = orderMapper.getByNumber(ordersPaymentDTO.getOrderNumber());
        if (existingOrder == null) {
            throw new OrderBusinessException("订单不存在");
        }
        if (existingOrder.getPayStatus() == Orders.PAID) {
            log.warn("订单{}已支付，拒绝重复支付请求", ordersPaymentDTO.getOrderNumber());
            throw new OrderBusinessException("订单已支付");
        }
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
        Orders order = orderMapper.getByNumber(orderNumber);
        if (order == null) throw new OrderBusinessException("订单不存在");
        if (order.getPayStatus() == Orders.PAID) {
            log.info("订单{}已支付，跳过模拟回调", orderNumber);
            return; // 安全退出，避免进入 paySuccess
        }
        paySuccess(orderNumber);
    }

    @Override
    public Orders getByOrderNumber(String orderNumber) {
        return orderMapper.getByNumber(orderNumber);
    }

    @Override
    public void reminder(Long id) {
        Orders order = orderMapper.getById(id);
        if (order == null) throw new OrderBusinessException("订单不存在");

        Map<String, Object> map = new HashMap<>();
        map.put("type", 2);
        map.put("orderId", id);
        map.put("content", "订单号：" + order.getNumber());
        String json= JSON.toJSONString(map);
        webSocketServer.sendToAllClient(json);
    }

    /**
     * 支付成功，修改订单状态
     *
     * @param outTradeNo
     */
    /**
     * 支付成功，修改订单状态（幂等处理）
     */
    /**
     * 支付成功，修改订单状态（幂等处理）
     */
    public void paySuccess(String outTradeNo) {
        log.info("支付成功回调，订单号：{}", outTradeNo);

        // 1. 查询订单
        Orders ordersDB = orderMapper.getByNumber(outTradeNo);
        if (ordersDB == null) {
            log.error("订单不存在，订单号：{}", outTradeNo);
            throw new OrderBusinessException("订单不存在");
        }

        // 2. 检查订单是否已支付
        if (ordersDB.getPayStatus() == Orders.PAID) {
            log.info("订单已支付，订单号：{}，跳过处理", outTradeNo);
            return;
        }

        // 3. 构建更新对象（必须包含ID）
        Orders updateParam = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .checkoutTime(LocalDateTime.now())
                .build();

        // 4. 【关键】执行更新并检查影响行数
        int updatedRows = orderMapper.update(updateParam);

        // 5. 如果更新失败（0行），说明已被其他线程处理
        if (updatedRows == 0) {
            Orders currentOrder = orderMapper.getById(ordersDB.getId());
            if (currentOrder != null && currentOrder.getPayStatus() == Orders.PAID) {
                log.warn("订单{}已被其他线程处理完成，跳过推送", outTradeNo);
                return;
            } else {
                log.error("订单更新失败，订单号：{}", outTradeNo);
                throw new OrderBusinessException("订单状态更新失败");
            }
        }

        log.info("订单状态更新成功，订单ID：{}，状态：待接单", ordersDB.getId());

        // 6. 安全推送（仅当更新成功时）
        Map<String, Object> map = new HashMap<>();
        map.put("type", 1);
        map.put("orderId", ordersDB.getId());
        map.put("content", "订单号：" + outTradeNo);
        map.put("timestamp", System.currentTimeMillis());

        try {
            webSocketServer.sendToAllClient(JSON.toJSONString(map));
            log.info("WebSocket推送新订单消息成功 | 订单号: {}", outTradeNo);
        } catch (Exception e) {
            log.error("WebSocket推送失败 | 订单号: {}", outTradeNo, e);
        }
    }
}