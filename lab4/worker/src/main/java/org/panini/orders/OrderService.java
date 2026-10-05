package org.panini.orders;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    @Autowired
    private OrderRepository orderRepository;

    @Transactional
    public Order process(long orderNumber) {
        return orderRepository.markProcessed(orderNumber)
                .orElseThrow(() -> new OrderNotFoundException(orderNumber));
    }
}
