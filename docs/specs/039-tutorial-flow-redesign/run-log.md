# Run Log: 039-tutorial-flow-redesign

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 21:22 | implementer | `git fetch origin dev` + `gh pr list/view` | origin/dev 최고 V35, 열린 PR migration 없음 → V36 선택 (ADR-0004) |
| 21:22 | implementer | `.\gradlew.bat compileJava` | task 1 attempt·risk·order 귀속 모델 컴파일 통과 |
| 21:22 | implementer | `.\gradlew.bat compileTestJava` | 기존 테스트 소스와 production API 호환 컴파일 통과 |
| 21:22 | implementer | `.\gradlew.bat spotbugsMain spotlessCheck` | 신규 엔티티 정적 분석·Java 포맷 통과 |
| 21:38 | implementer | `.\gradlew.bat compileJava compileTestJava` | task 2 attempt API·주문 귀속·최초 위험 snapshot 컴파일 통과 |
| 21:38 | implementer | `.\gradlew.bat spotbugsMain --rerun-tasks` | 신규 서비스·DTO·controller 정적 분석 통과 |
| 21:42 | implementer | `.\gradlew.bat compileJava` | order-owned attribution port 리팩터링 후 production 컴파일 통과 |
| 21:42 | implementer | `.\gradlew.bat compileTestJava` | 기존 직접 생성 테스트 3곳의 신규 port mock 인자 미반영으로 컴파일 실패, tester 이관 |
| 21:46 | implementer | `.\gradlew.bat compileJava` | 지정가 체결 attempt→order preflight 잠금 순서 수정 후 production 컴파일 통과 |
| 21:50 | implementer | `.\gradlew.bat compileJava` | 완료 attempt ensure의 무변경 REPLAY 응답 분기 수정 후 컴파일 통과 |
| 22:00 | implementer | `.\gradlew.bat spotlessApply compileJava compileTestJava spotlessCheck` | task 3 원자 재시작·보상 매도·완료 replay와 API 계약 동기화 후 production/test 컴파일·포맷 통과 |
| 22:01 | implementer | `.\gradlew.bat spotbugsMain` | 신규 재시작 서비스·DTO와 attempt 상태 전이 정적 분석 통과 |
| 22:03 | implementer | `.\gradlew.bat spotlessApply compileJava` | 일반 지정가 샘플 BUY·SELL의 attempt 선잠금·현재 run 귀속 보강 후 production 컴파일 통과 |
| 22:28 | implementer | `.\gradlew.bat spotlessApply compileJava` | task 4 versioned 순수 generator·29+1 chart·canonical 주문/관찰/재시작 연결 production 컴파일 통과 |
| 22:28 | implementer | `.\gradlew.bat compileTestJava` | canonical 가격이 추가된 attribution DTO/port와 서비스 생성자에 기존 테스트 8개가 미동기화되어 17건 컴파일 실패, tester 이관 |
| 22:29 | implementer | `.\gradlew.bat spotlessCheck spotbugsMain` | task 4 신규 generator·응답 DTO·서비스 포맷과 production 정적 분석 통과 |
| 22:35 | implementer | `.\gradlew.bat compileJava` + production 2파일 `spotlessCheck -PspotlessIdeHook=...` | GET chart를 순수 조회로 고정하고 명시적 POST tick으로 canonical 지정가 정산을 분리한 production 컴파일·대상 포맷 통과. 전역 포맷은 tester 작업 중 테스트 8파일 때문에 미통과 |
| 22:51 | implementer | `.\gradlew.bat compileJava` | task 5 attempt/current-run 진행·관찰·복기·완료/reward 통합과 replay 수량 evidence production 컴파일 통과 |
| 23:00 | implementer | `.\gradlew.bat spotlessApply/spotlessCheck -PspotlessIdeHook=...` + `.\gradlew.bat compileJava` | task 5 변경 production 파일 포맷과 current-run BUY·SELL·잔여 수량 계약 보강 후 컴파일 통과 |
| 23:09 | implementer | production 파일 대상 `spotlessApply/spotlessCheck` + `.\gradlew.bat compileJava` | 영속 attempt가 없는 기존 샘플은 주문 비귀속·026 chain·session 가격 경로를 유지하고, attempt가 있으면 current-run 검증을 legacy evidence로 우회하지 않도록 rollout 호환 수정 후 컴파일 통과 |
| 23:52 | implementer | backend `compileJava`/대상 `spotlessCheck`, frontend `lint`/`build` | legacy completion의 lazy 완료 replay attempt·완료시각 고정 chart와 주문 목록 attempt/run 귀속 노출, frontend 정확한 run pending 복원 구현 검증 |
| 00:20 | tester | backend `gradlew build`, frontend 17 tests + `lint` + `build` | backend 4,124 tests, 실패 0, skip 1, 3분 49초; frontend 전 게이트 통과 |
| 00:20 | planner | PRD §3·036 tasks/run-log 최종 동기화 | Backend PR #381 / companion frontend PR #30, TUTORIAL-FLOW-001~012 완료 근거 |
| 2026-08-17 | implementer | 매도 이후 관찰을 막던 세 지점(관찰 서비스의 무조건 차단 가드 1곳 + 복기 서비스·진행 조회의 evidence 배제 필터 2곳) 제거 + api-contracts 동기화 (Gradle 미실행, 검증은 메인 세션 위임) | 026 spec.md "매도 여부와 무관하게" 원칙을 031이 상속, 이슈 #420 프로덕션 재현 |

## 모니터링 (사람용 요약)
- 21:22 — V36 추가형 migration, attempt·risk 엔티티/Repository, nullable 주문 run 귀속 구현 및 컴파일 통과.
- 21:38 — 멱등 진입·종목 선택 API와 샘플 주문 현재 run 귀속, 최초 BUY -3%/+5% snapshot 연결 완료.
- 21:42 — order→education 직접 의존을 order-owned port로 역전; 기존 테스트 생성자 3곳 갱신 필요.
- 21:46 — 지정가 체결이 attempt를 먼저 잠그고 restart 후 취소 주문은 상태 재확인에서 no-op 하도록 교착 위험 제거.
- 21:50 — 완료 attempt ensure는 상태·run을 변경하지 않고 `mode=REPLAY`로 반환하도록 수정.
- 22:00 — attempt→현재 run 주문(ID ASC)→account→holding 잠금 순서로 pending 예약과 순보유를 정리하고, canonical price 보상 SELL 감사 원장 뒤에만 run을 증가시키는 명시적 재시작 API 구현.
- 22:03 — 일반 지정가 `/api/orders/limit`의 샘플 BUY·SELL도 account/holding보다 attempt를 먼저 잠그고 현재 run에 귀속해 재시작 취소·예약 반환 대상에 포함.
- 22:28 — 영속 seed/version/anchor 기반 3초=1분 29+1 차트와 canonical close를 추가하고 샘플 시장가·일반/교육 지정가·관찰·보상 SELL 가격원을 통합.
- 22:35 — GET chart의 체결 side effect를 제거하고 attempt 선잠금 기반 `POST .../{market}/tick`에서만 current-run 지정가를 canonical 가격으로 정산하도록 분리.
- 23:00 — 샘플 진행 정본을 favorite/intention에서 영속 attempt/current run snapshot·원장으로 전환하고 completion/reward와 immutable replay, BUY·SELL·잔여 수량 evidence를 통합.
- 23:09 — 영속 attempt 존재 여부를 새 흐름의 명시적 경계로 삼아 기존 샘플의 비귀속 주문과 chain 관찰·복기·완료/만료/재시도를 보존하고, attempt가 있는 사용자는 current-run 검증을 우회하지 못하게 고정.
- 23:52 — migration 이전 completion 사용자는 실제·샘플 reflection 종목으로 완료 replay attempt를 lazy 생성하고 risk snapshot 없이 legacy 완료 evidence를 유지하며, 주문 목록 attempt/run으로 frontend stale pending 채택을 차단.
- 00:20 — Backend PR #381은 전체 build 4,124 tests(실패 0, skip 1, 3분 49초), companion frontend PR #30은 17 tests·lint·build를 통과했고 TUTORIAL-FLOW-001~012 및 최종 문서/PRD 동기화를 완료.
- 2026-08-17 — 이슈 #420: 매도 이후 관찰을 막던 세 지점을 제거했다. 관찰 서비스(`PracticeHoldingObservationService`)의 매도 체결 시 무조건 409 `PRACTICE_STEP_LOCKED` 차단 가드 1곳, 복기 서비스(`PracticeHoldingReflectionService`)와 진행 조회(`InvestmentPracticeQueryService`)에서 매도 체결 이후 관찰을 evidence에서 배제하던 필터 2곳이다. 매도 후 evidence를 채운 사용자의 영구 409를 해소하고 현재 run 귀속(risk snapshot 시각) 판정만 남겼다.
