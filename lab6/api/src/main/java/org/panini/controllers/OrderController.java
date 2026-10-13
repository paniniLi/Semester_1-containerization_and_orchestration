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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.List;

@RestController
public class OrderController {

    private final OrderRepository orderRepository;
    private final String version;
    private final boolean fail;
    private final boolean flaky;

    public OrderController(
            OrderRepository orderRepository,
            @Value("${api.version:v1}") String version,
            @Value("${api.fail:false}") boolean fail,
            @Value("${api.flaky:false}") boolean flaky) {

        this.orderRepository = orderRepository;
        this.version = version;
        this.fail = fail;
        this.flaky = flaky;
    }

    @PostMapping("/order")
    @ResponseStatus(HttpStatus.CREATED)
    public Order create(@Valid @RequestBody CreateOrderRequest request) {

        if ("v2".equals(version) && fail) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "v2 forced failure"
            );
        }

        if ("v1".equals(version) && flaky
                && ThreadLocalRandom.current().nextDouble() < 0.3) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "v1 random failure"
            );
        }

        return orderRepository.create(request.description().trim());
    }

    @GetMapping("/orders")
    public List<Order> findAll() {
        return orderRepository.findAll();
    }
}
