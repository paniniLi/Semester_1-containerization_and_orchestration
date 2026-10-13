package org.panini.orders;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateOrderRequest(
        @NotBlank(message = "description must not be blank")
        @Size(max = 500, message = "description must not exceed 500 characters")
        String description
) {
}
