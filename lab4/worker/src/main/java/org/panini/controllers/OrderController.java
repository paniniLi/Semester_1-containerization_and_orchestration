package org.panini.controllers;

import org.panini.orders.Order;
import org.panini.orders.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    @Autowired
    private OrderService orderService;

    @PostMapping("/orders/{orderNumber}/process")
    public Order process(@PathVariable long orderNumber) {
        return orderService.process(orderNumber);
    }
}
