package org.panini.orders;

public enum OrderStatus {
    CREATED(0),
    PROCESSED(1),
    FAILED(3);

    private final int code;

    OrderStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
