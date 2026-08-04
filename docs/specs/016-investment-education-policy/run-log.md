# Run Log: 016-investment-education-policy

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 02:19 | reviewer(리뷰) | `git diff dev...HEAD` (PR #173, feat/172-favorite-delete) | conventions.md, ADR-0002, ADR-0003, spec.md candidate 3 |
| 03:40 | reviewer(리뷰) | `git diff 5d6cb00863f18d00b60982b476b60e96b6bd0b02...HEAD` (PR #176, feat/175-practice-intention, candidate 4 고유분만) | conventions.md, ADR-0002, ADR-0003, ADR-0004, spec.md candidate 4 |
| 04:10 | reviewer(리뷰, 후속) | `git diff HEAD` (작업트리, PR #176 후속 수정) | conventions.md, ADR-0002, ADR-0003, ADR-0004 — 03:40 권장 1건 재검증 |
| 05:00 | implementer(#193 항목1) | `V18__drop_favorites_and_practice_intentions.sql` 신규 작성, `.\gradlew.bat compileJava` | tasks.md #193 항목1, ADR-0004, ADR-0012 |
| 05:40 | implementer(#193 항목2) | `Favorite`를 record POJO로, `FavoriteRepository` 삭제, `FavoriteService`를 ConcurrentHashMap+ReentrantLock 인메모리로 재작성, `.\gradlew.bat compileJava` | tasks.md #193 항목2, plan.md 데이터 모델·트랜잭션과 경합, ADR-0012 |
| 06:20 | implementer(#193 항목3) | `PracticeIntention`을 record로, `PracticeIntentionRepository`를 JPA→ConcurrentHashMap 인메모리로, `PracticeIntentionService` 재작성(progress DB 락 → favorite in-memory 락 순서 유지, `withFavoriteLock`+`isFavorited` 사용), `FavoriteService.lockFavoriteIfPresent` 제거·`isFavorited` 추가, `.\gradlew.bat compileJava` | tasks.md #193 항목3, plan.md 트랜잭션과 경합(progress→favorite 잠금 순서), ADR-0012 |
| 07:00 | implementer(#193 항목4) | `SyntheticPriceController`/`SyntheticPriceService`/`SyntheticPriceSeriesResponse` 신규 작성(저장소 없음), `docs/api-routes.md`·`docs/api-contracts.md` 신규 라우트·계약 추가, `.\gradlew.bat compileJava` | tasks.md #193 항목4, plan.md "합성 시세 생성 규칙", 오케스트레이터 확정값(제목=Instrument.name, 100틱, ±1%, PriceQueryService 기반 시작가) |

## 모니터링 (사람용 요약)
- 02:19 — PR #173(candidate 3 즐겨찾기 해제) 리뷰 완료, 차단 0건 — 권장 2건, 머지 가능.
- 03:40 — PR #176(candidate 4 투자 실습 사전 의도 기록) 리뷰 완료, 차단 0건 — 권장 1건 / 참고 1건, 머지 가능.
- 04:10 — PR #176 후속: IllegalStateException을 BusinessException(INTERNAL_ERROR)로 교체하고 도달 불가 근거 주석 추가, 단위 테스트(progress row 누락 시 INTERNAL_ERROR·잠금/저장 미호출) 신규 추가 확인. GlobalExceptionHandler가 BusinessException을 ErrorCode.getHttpStatus()(500)와 공통 ErrorResponse 포맷으로 정상 매핑함을 확인. 회귀 없음, 차단 0건, 머지 가능.
- 05:00 — #193 항목1: V18에서 practice_intentions → favorites 순서로 DROP(둘 다 다른 테이블에서 참조되지 않아 순서 무관, FK 없음 확인), V14·V16 미수정, compileJava 통과.
- 05:40 — #193 항목2: Favorite를 인메모리 record로, FavoriteService를 사용자별 ConcurrentHashMap+ReentrantLock(`withFavoriteLock`, 하위호환 `lockFavoriteIfPresent` 유지)으로 재작성, FavoriteController·DTO·오류코드 계약 불변, compileJava 통과. 테스트는 항목5에서 별도 처리 예정(현재 FavoriteRepositoryTest 등 기존 테스트는 컴파일 깨질 수 있음, 확인 필요).
- 06:20 — #193 항목3: PracticeIntention을 인메모리 record로, PracticeIntentionRepository를 사용자별 ConcurrentHashMap+AtomicLong 인메모리 저장소로 재작성, PracticeIntentionService에서 progress(DB, PESSIMISTIC_WRITE) 락을 먼저 잡고 그 트랜잭션 안에서 favoriteService.withFavoriteLock(in-memory)을 이어 잡아 isFavorited 확인+intention 저장을 원자적으로 수행하도록 재작성. FavoriteService.lockFavoriteIfPresent(@Deprecated)는 유일 호출부가 사라져 제거하고 isFavorited(조회 전용) 추가. Controller·DTO·오류코드 계약 불변, compileJava 통과. 기존 PracticeIntentionServiceTest·PracticeIntentionConcurrencyIntegrationTest·FavoriteConcurrencyIntegrationTest·PracticeRepositoryTest는 제거된 API(lockFavoriteIfPresent, JPA 엔티티 getter 등) 참조로 테스트 컴파일이 깨질 것으로 예상 — 항목5(tester)에서 재작성 필요.
- 07:00 — #193 항목4: `GET /api/education/practice/synthetic-prices/{instrumentId}` 신규(`InstrumentService.getInstrumentEntity`로 존재만 확인, tradable 불문 → 없으면 404 NOT_FOUND). `title`=`Instrument.name`, `tickSeconds=3`, `prices` 100개(시작가 포함, BigDecimal 정수). 시작가는 `PriceQueryService.getPriceQuote`(non-throwing)로 실제 현재가 조회, UNAVAILABLE이면 고정 fallback 10,000 사용(해당 종목의 "마지막 유효 종가"를 별도 조회하는 저장소가 없고 순수 참고용 차트라 실제 판정에 영향 없어 고정값 선택). 매 틱 -1%~+1% 균등분포 곱연산, 시작가 50% 미만 clamp. 저장소·잠금·트랜잭션 없음(읽기 전용). `docs/api-routes.md`·`docs/api-contracts.md`에 신규 라우트·계약 추가. compileJava 통과, 기존 테스트 영향 없음(신규 파일만 추가).
| 08:20 | reviewer(리뷰) | `git diff dev...HEAD` (worktree feat/193-tutorial-in-memory, HEAD 5951143) | conventions.md, ADR-0002, ADR-0003, ADR-0004, ADR-0012, docs/specs/010-deployment/spec.md |

- 08:20 — #193(즐겨찾기·사전 의도 인메모리 전환 + 합성 시세) 리뷰 완료. 동시성(ReentrantLock 재진입·잠금 순서 progress→favorite)·V18 마이그레이션·랜덤워크 로직 정확, 배포 spec과 충돌 없음(단일 인스턴스). 차단 0건 — 권장 2건(진행률 행 롤백 실증 테스트 공백, plan/tasks가 명시한 신규 서브패키지 미적용) / 참고 2건, 머지 가능.
