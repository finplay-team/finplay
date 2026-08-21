// 현재 재생세션의 준비 여부와 원본 거래일만 담아 market 밖으로 넘기는 DTO — StockReplaySession 엔티티를 노출하지 않는다.
package com.finplay.api.domain.market.service;

import java.time.LocalDate;

/**
 * {@code getMarketStatus()}는 개장·폐장만 알려 주고 <b>원본 거래일을 알 수 없어</b>, 재생 중인 거래일 기준으로
 * 무언가를 만들어야 하는 호출부(예: 개장 전 배치)가 쓸 수 없다. 그 자리를 메우는 최소 형태다.
 *
 * <p>{@code ready}가 {@code false}면 {@code sourceTradingDate}는 항상 {@code null}이다 — 준비되지 않은 세션의
 * 후보 거래일은 확정 전 값이라 노출하면 아직 재생하지 않을 날짜가 새어 나간다.
 */
public record StockReplaySessionDto(boolean ready, LocalDate sourceTradingDate) {
}
