package com.widyu.pay;

import java.util.Arrays;

public enum PointChargePackage {
    POINT_10000("POINT_10000", "포인트 충전 10,000원", 10000),
    POINT_30000("POINT_30000", "포인트 충전 30,000원", 30000),
    POINT_50000("POINT_50000", "포인트 충전 50,000원", 50000);

    private final String id;
    private final String orderName;
    private final int amount;

    PointChargePackage(String id, String orderName, int amount) {
        this.id = id;
        this.orderName = orderName;
        this.amount = amount;
    }

    public String getId() {
        return id;
    }

    public String getOrderName() {
        return orderName;
    }

    public int getAmount() {
        return amount;
    }

    public static PointChargePackage fromId(String id) {
        return Arrays.stream(values())
                .filter(value -> value.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 결제 패키지입니다: " + id));
    }
}
