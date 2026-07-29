# Run Log: 006-portfolio-query

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 15:20 | implementer | `./gradlew test --tests com.finplay.api.order.repository.OrderRepositoryTest` | plan.md Repository 설계 JPQL, ADR-0002 |
| 15:40 | implementer | `./gradlew compileJava` | plan.md 응답 DTO 설계(8필드), spec PORT-003 체결 필드 미노출 |
| 16:05 | implementer | `./gradlew test --tests com.finplay.api.order.service.OrderServiceTest` | plan.md Service 설계(`getMyOrders`), 컨벤션 mock 응답 객체 금지 규칙 |
| 16:25 | implementer | `./gradlew test --tests com.finplay.api.order.controller.OrderControllerTest` | plan.md Controller 설계(`GET /api/orders`), tasks.md 항목4 jsonPath 필드·401 검증 지침 |
| 16:45 | implementer | `./gradlew test --tests com.finplay.api.order.service.OrderListIntegrationTest` | plan.md 테스트 계획(통합), tasks.md 항목5, `OrderBuyIntegrationTest`(#13/PR#88) MutableClock 픽스처 패턴 |
| 17:10 | implementer | `./gradlew test --tests com.finplay.api.portfolio.service.HoldingValuationServiceTest` | 이슈 #47 plan.md "공통 진입점 설계"·"계산 규칙 확정" 절, PRD C-003 |
| 17:25 | implementer | `./gradlew compileJava compileTestJava && ./gradlew test --tests com.finplay.api.portfolio.service.HoldingValuationServiceTest` | 이슈 #47 plan.md "경계 케이스 처리표", tasks.md 항목2 |
| 22:16 | implementer | `./gradlew build --no-daemon --max-workers=1` (JAVA_HOME=corretto-17, Docker Desktop 기동 후) | 이슈 #47 tasks.md 항목3, plan.md "이 계산 진입점은 비throw `getPriceQuote`만 사용" 규칙 |
| 22:40 | reviewer(리뷰) | `git diff origin/dev...HEAD` | conventions.md, ADR-0002/0003/0004, PRD C-003 |
| 23:35 | implementer | `./gradlew compileJava compileTestJava && ./gradlew test --tests com.finplay.api.portfolio.repository.HoldingRepositoryTest --tests com.finplay.api.portfolio.service.HoldingValuationServiceTest` | 이슈 #81 plan.md "Repository 설계"·"Service 설계"(HoldingRepository는 portfolio 도메인이 직접 소유, ADR-0002) |
| 23:50 | implementer | `./gradlew compileJava` | 이슈 #81 plan.md "응답 DTO 설계"(6필드, `of(...)` 팩토리), tasks.md 항목2 |
| 23:58 | implementer | `./gradlew test --tests com.finplay.api.account.service.AccountServiceTest` | 이슈 #81 plan.md "Service 설계"(`getAccountSummary`, `@RequiredArgsConstructor` 교체), tasks.md 항목3 |
| 14:48 | implementer | `./gradlew compileJava compileTestJava && ./gradlew test --tests com.finplay.api.account.controller.AccountControllerTest` | 이슈 #81 plan.md "Controller 설계"(`GET /api/accounts/summary`, `account.domain.Market` 타입 주의), tasks.md 항목4 |
| 00:07 | implementer | `./gradlew build --no-daemon --max-workers=1` (JAVA_HOME=corretto-17, Docker 기동 후) | 이슈 #81 plan.md "테스트 계획"(통합), tasks.md 항목5, `OrderBuyIntegrationTest`(#13)·`OrderListIntegrationTest`(#21) MutableClock 픽스처 패턴 |
| 09:15 | reviewer(리뷰) | `git diff origin/dev...HEAD` (SHA ab1db36) | conventions.md, ADR-0002/0003/0004, agent-mistakes.md 2026-07-30 항목 |
| 16:20 | implementer | `./gradlew test --tests "*AccountServiceTest"` | PR #96 리뷰 차단 반영 — 시세 무효 보유 합산 정책을 원가(costBasis) 반영 방식으로 수정 |

## 모니터링 (사람용 요약)
- 15:20 — OrderRepository에 사용자별 최신순 조회 JPQL 추가, DataJpaTest 3건(본인만/정렬/빈목록) 통과.
- 15:40 — OrderListItemResponse record 추가(8필드, 체결 전용 필드 없음 확인), 컴파일 통과.
- 16:05 — OrderService.getMyOrders 추가(readOnly 트랜잭션), 단위 테스트 2건(매핑 필드·빈 목록) 포함 16건 전체 통과.
- 16:25 — OrderController에 GET /api/orders 추가, jsonPath 8필드 검증+체결 전용 필드 부재(doesNotExist)+401 테스트 2건 포함 13건 전체 통과.
- 16:45 — 신규 `OrderListIntegrationTest`(Testcontainers) 추가: 매수 API로 owner 2건·other 1건 생성 후 본인 범위·최신순·필드 계약·타인 제외 검증 + 신규 사용자 빈 배열 케이스, 2건 통과.
- 17:10 — 이슈 #47: `HoldingValuationDto`(7필드)·`HoldingValuationService.evaluateHolding`(비throw `getPriceQuote` 사용) 추가, 정상 케이스(이익·손실) 단위 테스트 2건 통과. controller 없어 문서 동기화 대상 아님.
- 17:25 — 이슈 #47 항목2: 기존 로직이 경계 케이스 처리표와 이미 일치함을 코드 대조로 확인, 경계 케이스 단위 테스트 3건(시세 무효/보유수량 0/평균단가 0) 추가해 총 5건 통과.
- 22:16 — 이슈 #47 항목3: `HoldingValuationService`가 `getPriceQuote(Instrument)`만 호출함(throw 변형 `getPrice` 미사용) 코드 재확인, order·portfolio·market 전 스위트 회귀 없음, `./gradlew build` 전체(Spotless·SpotBugs·JaCoCo 40%) BUILD SUCCESSFUL.
- 22:40 — 이슈 #47 리뷰 완료: double/float 없음, 비throw getPriceQuote만 사용, 저장 경로 없음, controller 미추가 확인. 차단 0건 / 권장 0건 / 참고 1건, 머지 가능.
- 23:35 — 이슈 #81 항목1: `HoldingRepository.findAllByAccountIdAndIsActiveTrue`(JOIN FETCH) 추가, `HoldingValuationService.evaluateActiveHoldingsForAccount` 추가(account 도메인의 유일 진입점). DataJpaTest 4건·기존 서비스 단위테스트에 2건 추가(총 7건) 전체 통과. controller 없어 문서 동기화 대상 아님.
- 23:50 — 이슈 #81 항목2: `AccountSummaryResponse` record 추가(6필드: cashBalance·holdingsValue·totalValue·realizedPnl·unrealizedPnl·returnRate, `of(...)` 정적 팩토리). 컴파일 통과. controller 미변경이라 api-routes.md·api-contracts.md 갱신 대상 아님(controller 작업 항목에서 처리).
- 23:58 — 이슈 #81 항목3: `AccountService`에 `HoldingValuationService` 의존성 추가(`@RequiredArgsConstructor`로 교체)·`getAccountSummary` 구현(AVAILABLE만 합산, `totalValue`·`returnRate` 계산). 단위 테스트 5건(유효만/무효 혼합 제외/활성 보유 없음/계좌 없음 NOT_FOUND 회귀 포함, 기존 2건 유지) 총 7건 통과, account·portfolio 스위트 회귀 없음. controller 미변경이라 문서 동기화 대상 아님(다음 항목에서 처리).
- 14:48 — 이슈 #81 항목4: 신규 `com.finplay.api.account.controller.AccountController` 추가(`GET /api/accounts/summary`, `account.domain.Market` 타입 사용, 별도 검증 코드 없음). 신규 `AccountControllerTest`(WebMvc 슬라이스) 5건(STOCK·CRYPTO 200 필드 계약, market 누락 400, market=FOREX 400, 인증 실패 401) 전체 통과. controller 신규 추가지만 문서 동기화(prd.md·api-routes.md·api-contracts.md)는 다음 항목(통합 테스트 이후 마지막 항목)에서 처리 예정.
- 00:07 — 이슈 #81 항목5: 신규 `AccountSummaryIntegrationTest`(Testcontainers) 추가 — 회원가입 직후 0값(시드머니만 예외), 매수 후 원장·최신 분봉가 기준 6필드 정확 일치, 크립토 피드 단절로 시세 무효화된 보유 제외(예외 없음), 타인 매수가 본인 조회에 미섞임 4건. 클래스에 `@Transactional`을 추가해 각 테스트 종료 시 자동 롤백시킴 — 없으면 이 클래스가 `com.finplay.api.account...`로 다른 통합 테스트(`order...`)보다 알파벳순으로 먼저 실행되어 커밋된 종목·주문 데이터가 `InstrumentRepositoryTest`(정확히 28건 단정)·`StockReplaySessionRepositoryTest`를 깨뜨림을 재현·확인함(사람이 실행하는 CI 등 실제 실행 순서에서도 같은 문제가 재발할 수 있어 agent-mistakes.md에 기록). `./gradlew build --no-daemon --max-workers=1` 전체(936개 테스트, Spotless·SpotBugs·JaCoCo 40%) BUILD SUCCESSFUL.
- 09:15 — 이슈 #81 리뷰 완료: ADR-0002 준수(account가 HoldingRepository 미직접참조, HoldingValuationService 단일 진입점) 확인, `account.domain.Market` 타입 정확 사용 확인, 시세 무효 종목 원가까지 완전 제외 확인, money 계산 전 구간 BigDecimal/long(double·float 없음) 확인, 문서(prd.md·api-routes.md·api-contracts.md) 코드와 일치 확인, `AccountSummaryIntegrationTest`의 `@Transactional`은 agent-mistakes.md 기록된 문제(다른 슬라이스 테스트 카운트 단정 충돌) 해결을 위한 기존 전례(CommunityPostDetailIntegrationTest 등)와 동일한 패턴이며 같은 물리 트랜잭션 내에서 서비스 호출이 이뤄져 요약 수치 정확성 검증 자체는 약화되지 않음(다만 실제 커밋 경계 검증은 대상 밖). 차단 0건 / 권장 0건 / 참고 1건, 머지 가능.
- 16:20 — PR #96 리뷰 차단 1건 반영: 시세 무효(휴장) 보유를 합산에서 완전 제외하던 정책이 장 마감 시간대 전종목 UNAVAILABLE 상황에서 실손실 없이 holdingsValue=0·수익률 대폭 마이너스로 보이는 오류를 냄(QA 재현). `AccountService.getAccountSummary`를 수정해 UNAVAILABLE 보유는 `costBasis`를 `holdingsValue`에 가산(unrealizedPnl은 0 기여)하도록 변경. `AccountServiceTest` 혼합 케이스 테스트를 새 정책으로 갱신(7건 전체 통과), plan.md "이슈 #81" 절에 정책 수정 근거 기록, `docs/api-contracts.md` `## account` 절에 새 동작 명시. 통합 테스트(`AccountSummaryIntegrationTest`)는 별도 세션이 처리 예정이라 건드리지 않음.
