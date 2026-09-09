package com.finplay.api.domain.order.service;

import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;

public record PracticeRunFillKindDto(Long orderId, OrderSide side, OrderType orderType) {
}
