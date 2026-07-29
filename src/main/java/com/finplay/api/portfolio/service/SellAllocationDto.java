// FIFO lot 배분 결과로 얻은 원가·수수료 합계를 담아 OrderService에 전달하는 내부 DTO
package com.finplay.api.portfolio.service;

public record SellAllocationDto(long totalAllocatedCost, long totalAllocatedBuyFee) {
}
