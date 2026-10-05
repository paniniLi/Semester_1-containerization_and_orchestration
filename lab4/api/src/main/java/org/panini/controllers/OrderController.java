package org.panini.controllers;

import jakarta.validation.Valid;
import org.panini.orders.CreateOrderRequest;
import org.panini.orders.Order;
import org.panini.orders.OrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class OrderController {

    private final OrderRepository orderRepository;

    public OrderController(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @PostMapping("/order")
    @ResponseStatus(HttpStatus.CREATED)
    public Order create(@Valid @RequestBody CreateOrderRequest request) {
        return orderRepository.create(request.description().trim());
    }

    @GetMapping("/orders")
    public List<Order> findAll() {
        return orderRepository.findAll();
    }
}
