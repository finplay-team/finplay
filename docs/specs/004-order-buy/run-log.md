# Run Log: 004-order-buy

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `./gradlew compileJava` | plan.md 설계 노트 3·4·6·9, tasks.md 항목 1 |
| - | implementer | `./gradlew compileJava` | plan.md 설계 노트 6·7·패키지 구성, tasks.md 항목 2 |
| - | implementer | `./gradlew compileJava`, `./gradlew spotlessApply` | plan.md 설계 노트 1·2·3·5·8·입력 명세·패키지 구성, tasks.md 항목 3 |
| - | implementer | `./gradlew compileJava`, `./gradlew compileTestJava`, `./gradlew test --tests OrderControllerTest` | plan.md API 설계·패키지 구성, tasks.md 항목 4, docs/conventions.md API 응답 포맷 |
| - | implementer | `./gradlew clean build --no-daemon --max-workers=1` (JAVA_HOME=corretto-17) | plan.md 테스트 계획(통합 시나리오), tasks.md 항목 5, docs/agent-mistakes.md(JAVA_HOME·Docker) |

| - | reviewer(리뷰) | `git diff dev...HEAD --stat` 후 전체 diff 검토 | docs/conventions.md, ADR-0002/0003/0004, docs/agent-mistakes.md |
| - | implementer | `./gradlew compileJava compileTestJava test --tests "*OrderServiceTest"` | 리뷰 차단 1건 수정 — ADR-0002(도메인 간 참조는 service 레이어만) |
| - | implementer | `./gradlew compileJava compileTestJava`, `./gradlew test --tests OrderRepositoryTest --tests TradeRepositoryTest` | plan.md "이슈 #22" 신규 리포지토리 메서드 절, tasks.md 이슈 #22 항목 1 |
| - | implementer | `JAVA_HOME=corretto-17 ./gradlew compileJava compileTestJava`, `./gradlew test --tests "com.finplay.api.order.*"` | plan.md "이슈 #22 — 클래스 분리"·"OrderService.createOrder 확정 로직" 절, tasks.md 이슈 #22 항목 2 |

## 모니터링 (사람용 요약)
- 항목 1(account·market·common 확장 지점) 구현 완료, 컴파일 통과. 테스트는 tester 담당.
- 항목 2(Holding.applyBuy·HoldingRepository 조회·PortfolioBuyService) 구현 완료, 컴파일 통과. 테스트는 tester 담당.
- 항목 3(OrderCreateRequest·OrderService.createBuyOrder) 구현 완료, 컴파일 통과. 컴파일에 필요해 OrderResponse도 최소 형태로 함께 생성(항목 4에서 재사용/조정 가능). 테스트는 tester 담당.
- 항목 4(OrderController·OrderResponse 검토) 구현 완료. OrderResponse는 plan.md 응답 계약과 이미 일치해 필드 변경 없음. `@Validated`+`@NotBlank`로 헤더 공백값도 400 처리. `OrderControllerTest`(@WebMvcTest) 6케이스(성공·헤더누락·market/side 리터럴 오류·422·409·401) 작성해 통과 확인, 컴파일 통과. api-routes.md·api-contracts.md 갱신 완료.
- 항목 5(`OrderBuyIntegrationTest`) 구현 완료. 전역 Clock 빈을 테스트 전용 `@Primary` MutableClock으로 교체해 주식 장중·분봉 조회를 고정 시각(2026-07-29 수, 공휴일 아님)에 결정론적으로 재현 — 실제 시스템 시각에 의존하지 않음. 매수 성공(4테이블 원자 저장)·현금부족(4테이블 무흔적)·재매수(평균단가 재계산+lot 2건) 3케이스 모두 통과, `./gradlew clean build` 전체(Spotless·SpotBugs·JaCoCo 포함) 통과.
- 리뷰(코드리뷰 모드): 차단 1건(OrderService가 market 도메인의 InstrumentRepository를 직접 주입 — ADR-0002 위반, InstrumentService 등 market 서비스 경유로 리팩터 필요), 권장 1건(PortfolioBuyService 수동 생성자 → @RequiredArgsConstructor). 나머지 컨벤션·ADR·문서 동기화·테스트 레벨은 이상 없음.
- 차단 1건 수정 완료: `InstrumentService.getInstrumentEntity(Long)` 추가 후 `OrderService`가 `InstrumentRepository` 대신 이 메서드만 거치도록 리팩터. `OrderServiceTest` mock 대상도 `InstrumentService`로 교체. `OrderServiceTest` 14케이스 전부 통과, compileJava/compileTestJava 통과.
- 이슈 #22 항목 1(리포지토리 확장) 구현 완료: `OrderRepository.findByUserIdAndIdempotencyKey`(JOIN FETCH instrument), `TradeRepository.findByOrderId` 추가. `OrderRepositoryTest`에 존재/미존재/타사용자 케이스 3건 추가, 신규 `TradeRepositoryTest`(존재/미존재 2건) 작성. 9케이스 전부 통과(Testcontainers MySQL), compileJava/compileTestJava 통과.
- 이슈 #22 항목 2(`OrderExecutionService` 추출, 순수 리팩터링) 구현 완료: 검증→가격조회→체결→계좌/보유 갱신 로직 전체를 신규 `OrderExecutionService`로 이동(`execute(userId, idempotencyKey, requestHash, request)` 하나만 public, `@Transactional`). 기존 `OrderService`는 `getMyOrders`·`calculateRequestHash`만 유지하고 `createOrder`는 `orderExecutionService.execute(...)` 위임만 하는 임시 형태로 축소(동작 변경 없음, `OrderController` 무변경). 기존 `OrderServiceTest`(494줄)를 `OrderExecutionServiceTest`로 이름 변경 후 대상·호출부만 교체(고정 `requestHash="test-hash"`), 원래 있던 `getMyOrders` 케이스 2건은 대상 클래스에 해당 메서드가 없어 이 파일에서 제거(항목 3에서 새 슬림 `OrderServiceTest`로 재작성 예정 — 현재 `OrderService`는 무테스트 상태, 회귀 없음은 `OrderControllerTest`/통합 테스트로 확인). `com.finplay.api.order.*` 전 테스트(63케이스, Testcontainers 포함) 통과, compileJava/compileTestJava 통과.
