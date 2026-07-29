# Run Log: 011-order-ledger-schema

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 진행 중 | implementer | `./gradlew compileJava` | plan.md V10 테이블별 컬럼·열거형 3종 절, ADR-0004 |
| 진행 중 | implementer | `./gradlew compileJava` | plan.md 엔티티 필드 설계·Repository 5종 절 |
| 진행 중 | implementer | `./gradlew compileJava` | plan.md 엔티티 필드 설계 절(Holding·HoldingLot), ADR-0002(cross-domain 엔티티 참조 전례) |
| 진행 중 | implementer | `./gradlew compileJava` | plan.md 엔티티 필드 설계 절(TradeAllocation) |

## 모니터링 (사람용 요약)
- V10__create_order_ledger_tables.sql(5테이블) + OrderSide/OrderType/OrderStatus enum 3종 추가, 컴파일 통과.
- Order·Trade 엔티티 + OrderRepository·TradeRepository 추가, 컴파일 통과.
- tester 지적 반영: Order.requestHash에 columnDefinition="CHAR(64)" 추가(V10 CHAR(64)와 매핑 일치), 컴파일 통과.
- Holding·HoldingLot 엔티티 + HoldingRepository·HoldingLotRepository 추가(portfolio 패키지 신규), 컴파일 통과.
- TradeAllocation 엔티티 + TradeAllocationRepository 추가 — 5개 엔티티·5개 Repository 완성, 컴파일 통과.
