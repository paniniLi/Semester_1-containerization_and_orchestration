CREATE TABLE IF NOT EXISTS orders (
                                      order_number BIGSERIAL PRIMARY KEY,
                                      description VARCHAR(500) NOT NULL,
    status INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ NULL
    );

CREATE INDEX orders_status_idx ON orders (status);
