package org.panini.orders;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

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

    public Order create(String description) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO orders (description)
                VALUES (?)
                RETURNING order_number, description, status, created_at, processed_at
                """, ORDER_ROW_MAPPER, description);
    }

    public List<Order> findAll() {
        return jdbcTemplate.query("""
                SELECT order_number, description, status, created_at, processed_at
                FROM orders
                ORDER BY order_number
                """, ORDER_ROW_MAPPER);
    }
}
