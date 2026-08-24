# Tasks: 주문·체결 원장 스키마 구축

- [x] V10 마이그레이션(`orders`·`trades`·`holdings`·`holding_lots`·`trade_allocations` 5개 테이블, FK·유니크 제약 포함) + `OrderSide`·`OrderType`·`OrderStatus` enum 3종
- [x] `Order`·`Trade` 엔티티 + `OrderRepository`·`TradeRepository` (`order` 패키지)
- [x] `Holding`·`HoldingLot` 엔티티 + `HoldingRepository`·`HoldingLotRepository` (`portfolio` 패키지)
- [x] `TradeAllocation` 엔티티 + `TradeAllocationRepository` (`portfolio` 패키지)
- [x] `@DataJpaTest` 슬라이스 테스트 — 5개 엔티티 매핑 검증 + 유니크 제약 2종(`orders.user_id+idempotency_key`, `holdings.account_id+instrument_id`) 위반 테스트
- [x] `./gradlew build` 실행·통과 확인
