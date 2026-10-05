package org.panini.orders;

import java.time.OffsetDateTime;

public record Order(
        long orderNumber,
        String description,
        int status,
        OffsetDateTime createdAt,
        OffsetDateTime processedAt
) {
}
