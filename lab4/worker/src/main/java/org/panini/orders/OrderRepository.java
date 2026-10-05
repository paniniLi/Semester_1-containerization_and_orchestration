package org.panini.orders;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class OrderRepository {

    private static final RowMapper<Order> ORDER_ROW_MAPPER = (resultSet, rowNumber) -> new Order(
            resultSet.getLong("order_number"),
            resultSet.getString("description"),
            resultSet.getInt("status"),
            resultSet.getObject("created_at", java.time.OffsetDateTime.class),
            resultSet.getObject("processed_at", java.time.OffsetDateTime.class)
    );

    private final JdbcTemplate jdbcTemplate;

    public OrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Order> markProcessed(long orderNumber) {
        List<Order> updatedOrders = jdbcTemplate.query("""
                UPDATE orders
                SET status = ?, processed_at = CURRENT_TIMESTAMP
                WHERE order_number = ? AND status = ?
                RETURNING order_number, description, status, created_at, processed_at
                """, ORDER_ROW_MAPPER,
                OrderStatus.PROCESSED.code(), orderNumber, OrderStatus.CREATED.code());

        if (!updatedOrders.isEmpty()) {
            return Optional.of(updatedOrders.getFirst());
        }

        return findByOrderNumber(orderNumber);
    }

    private Optional<Order> findByOrderNumber(long orderNumber) {
        return jdbcTemplate.query("""
                SELECT order_number, description, status, created_at, processed_at
                FROM orders
                WHERE order_number = ?
                """, ORDER_ROW_MAPPER, orderNumber).stream().findFirst();
    }
}
