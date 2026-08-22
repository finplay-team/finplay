// order 도메인이 사용하는 튜토리얼 attempt ID와 실행 세대의 스칼라 귀속값
package com.finplay.api.domain.order.service;

import java.math.BigDecimal;

public record PracticeOrderAttributionDto(Long attemptId, long runNumber, BigDecimal canonicalPrice) {
}
