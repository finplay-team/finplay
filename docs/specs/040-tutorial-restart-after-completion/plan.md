# Plan: 완료 튜토리얼 attempt 재시작 허용 — 보상은 최초 완료 1회만

## 관련 문서

- Spec: `./spec.md`
- Issue: #402
- Delta 정본: `../039-tutorial-flow-redesign`(TUTORIAL-FLOW-005 대체), `../026-market-order-practice-tutorial`
  (완료·보상 원본), `../031-tutorial-sandbox-instruments`
- 관련 ADR: ADR-0002(도메인 기준 3계층), ADR-0003(MySQL Testcontainers), ADR-0012(튜토리얼 상태 저장 경계 —
  완료 evidence는 DB에 유지한다는 결정은 유지, 대체하지 않음)

새 ADR은 필요 없다. 이 기능은 기존 attempt/completion 테이블의 read 판정 로직만 바꾸며 저장 위치 결정을
바꾸지 않는다.

## 핵심 설계 결정 — 스키마 변경 없음

`practice_completions`(`UNIQUE(user_id, tutorial_key)`)는 이미 "이 사용자가 이 시장 튜토리얼을 최초로
완료한 시점" 그 자체를 기록하는 불변 행이다. 이 기능 배포 이전에 이미 완료한 사용자도 이 행이 이미
존재하므로, **"최초 완료 여부" 판정에 새 컬럼·새 테이블·백필이 필요 없다** — 기존 행의 존재 여부를 그대로
재사용하면 배포 시점과 무관하게 정확하다.

반대로 재완료 시 `practice_completions`·`practice_market_reflections`에 새 행을 추가하는 접근은 두 테이블의
`UNIQUE(user_id, tutorial_key)` 제약과 정면으로 충돌한다(제약을 풀면 "최초 완료" 판정 자체가 모호해지고
026/031/ADR-0012의 불변 완료 원칙이 깨진다). 따라서 이 기능은 **재완료 시 이 두 테이블에 쓰지 않는다** —
"현재 실행이 완료됐다"는 사실은 `practice_attempts.status`/`completed_at`(attempt별로 이미 재시작마다
재설정되는 필드)만으로 표현한다.

이 결정의 결과: `db/migration/`에 새 마이그레이션이 필요 없다. `orders.practice_attempt_id`/
`practice_attempt_run_number`(V38)와 `practice_risk_snapshots`의 `UNIQUE(attempt_id, run_number)`(V38)는
이미 재시작마다 새 run으로 자연 분리되므로 그대로 재사용된다.

## API 설계 — 새 엔드포인트 없음, 기존 3개의 동작·응답 변경

| Method | URL | 변경 내용 |
|---|---|---|
| POST | `/api/education/practice/attempts/{market}/restart` | `COMPLETED` 상태에서도 무변경 단락 반환을 하지 않고 실제 정리·재시작을 수행 |
| PUT | `/api/education/practice/attempts/{market}` | 매핑·요청·응답 스키마는 무변경. 다만 completion evidence가 있고 attempt가 현재 `COMPLETED`가 아닌 조합(재시작 후 진행 중)을 오류로 오판하던 내부 분기를 제거 |
| POST | `/api/education/practice/holding-reflections` | 응답에 `rewardGranted: boolean` 필드 추가(아래 "미확정" 참고). attempt 기반 완료 시 재완료를 더 이상 `PRACTICE_ALREADY_COMPLETED`로 막지 않음(단, attempt가 현재 `COMPLETED` 상태에서 재시작 없이 재호출하는 경우는 계속 막는다 — 그 경우는 "재완료"가 아니라 "같은 완료를 중복 제출") |

URL·HTTP 메서드·요청 바디는 변경하지 않는다. `docs/api-routes.md`의 매핑 표 자체는 바뀌지 않지만,
`docs/api-contracts.md`의 이 3개 계약(요청 검증표는 무변경, 응답·오류 의미는 변경)은 구현 커밋에서
갱신이 필요하다 — 아래 "controller 영향" 참고.

## 입력 명세

세 엔드포인트 모두 기존 요청 파라미터·바디를 그대로 사용한다. 새로 검증할 입력 필드는 없다.

| 필드 | 필수 | 검증 |
|---|---|---|
| `market`(path, 기존) | 필수 | 기존과 동일(`STOCK|CRYPTO`, 400 `VALIDATION_ERROR`) |
| `holdingId`(기존 `PracticeHoldingReflectionCreateRequest`) | 필수 | 기존과 동일 |
| `answer`(기존) | 필수 | 기존과 동일 — 재완료 요청에서도 형식 검증은 동일하게 통과해야 하지만 저장하지 않는다(TUTORIAL-RESTART-007) |

## 데이터 모델

**변경 없음.** 기존 테이블만 다르게 읽는다.

- `practice_attempts`(V38): `PracticeAttempt.restart()`의 `if (status == COMPLETED) throw` 가드를
  제거해 `COMPLETED`에서도 재시작이 `run_number += 1`, 상태 초기화, `completed_at = null`로 전이하게
  한다. 테이블 CHECK 제약(`chk_practice_attempts_completion_time` 등)은 이미 이 전이를 허용한다(재시작은
  기존에도 미완료 상태에서 동일한 필드 초기화를 수행해 왔다).
- `practice_completions`(V28), `practice_market_reflections`(V27), `practice_progresses`: 스키마·엔티티
  변경 없음. 재완료 트랜잭션에서 이 세 테이블에 `INSERT`/`UPDATE`를 실행하지 않는다.

## 소스 변경 대상과 근거

- `PracticeAttempt.restart(LocalDateTime)`(`domain`): `COMPLETED`일 때 던지는
  `IllegalStateException` 가드 제거. 나머지 전이 로직(런 증가, 필드 초기화)은 그대로 재사용된다.
- `PracticeAttemptRestartService.restart(Long, Market)`: `if (attempt.getStatus() ==
  PracticeAttemptStatus.COMPLETED) { return toResponse(attempt); }` 단락 분기를 제거해 완료 attempt도
  일반 미완료 attempt와 동일한 정리·재시작 경로(`practiceRunRestartOrderService.cleanupCurrentRun` →
  `attempt.restart(...)`)를 타게 한다. `PracticeRunRestartOrderService.cleanupCurrentRun`은 이미 "현재
  run의 순체결수량만 보상매도"로 설계돼 있어 완료된 run에도 그대로 적용 가능하다(코드 변경 불필요, 회귀
  테스트만 추가).
- `PracticeAttemptService.ensureAttempt(Long, Long, Market)`: `completion != null && attempt.getStatus() !=
  COMPLETED && !inserted` 조합을 더 이상 `PRACTICE_EVIDENCE_MISSING`으로 처리하지 않는다. 재시작 후
  진행 중인 attempt를 다시 진입 조회하면 이 조합이 정상적으로 발생하므로(completion evidence는 남아있지만
  attempt는 현재 `SELECTING_INSTRUMENT`/`IN_PROGRESS`) 그대로 현재 상태를 반환해야 한다. `completion !=
  null && attempt.getStatus() == COMPLETED`(진짜 REPLAY, TUTORIAL-RESTART-002) 분기는 그대로 유지한다.
- `PracticeHoldingReflectionService.createAttemptReflection(...)`: 현재 `progress.getStatus() ==
  COMPLETED || practiceCompletionRepository.findByUserIdAndTutorialKey(...).isPresent()` 조합에서 무조건
  `PRACTICE_ALREADY_COMPLETED`를 던진다. 이를 다음과 같이 분기한다.
  - attempt가 이미 `COMPLETED`(재시작하지 않고 같은 완료를 다시 제출) → 기존대로 `PRACTICE_ALREADY_COMPLETED`
    (이미 위쪽에서 `attempt.getStatus() == COMPLETED` 체크로 걸러진다 — 이 체크는 변경하지 않는다).
  - attempt가 `COMPLETED`가 아니고(재시작 후 진행 중) `practice_completions` 행이 이미 존재 → **재완료**:
    `practiceMarketReflectionRepository.save(...)`와 `practiceCompletionRepository.save(...)`를 호출하지
    않고, `progress.complete(...)`도 호출하지 않는다(이미 `COMPLETED`인 진행 상태를 다시 완료 처리하면
    `PracticeProgress.complete()`가 `IllegalStateException`을 던진다 — 026/031이 의도한 불변 가드이므로
    그대로 둔다). `attempt.complete(now)`만 호출하고 보상 지급을 건너뛴다.
  - attempt가 `COMPLETED`가 아니고 `practice_completions` 행이 없음 → **최초 완료**: 기존 로직 그대로
    (`reflection` 저장, `completion` 저장, `progress.complete(...)`, `attempt.complete(now)`, 보상 지급).
  - 판정 순서: `practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(...)`로 잠근 뒤
    `practiceCompletionRepository.findByUserIdAndTutorialKey(...)`를 호출해 "이번 트랜잭션 시작 시점의
    존재 여부"를 읽는다(TUTORIAL-RESTART-006, 아래 "동시성" 참고).
  - evidence 검증(`practiceAttemptEvidenceService.requireCurrentRun`, `verifyAttemptSaleEvidence`, 관찰
    evidence 존재)은 재완료·최초 완료 모두 동일하게 통과해야 한다 — 분기 이전에 그대로 유지한다.
- `PracticeHoldingReflectionResponse`: `rewardGranted` 필드 추가(미확정, 아래 참고).

## 동시성 설계 — TUTORIAL-RESTART-006

같은 사용자의 같은 `tutorial_key`에 대한 재완료 요청은 이미 `createAttemptReflection`이 호출하는
`practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(userId, tutorialKey)`로 직렬화된다(기존
락, 변경 없음). 이 락은 attempt·run과 무관하게 `(user_id, tutorial_key)` 단위이므로 동시에 들어온 재시작
경합·재완료 경합 모두 이 하나의 락으로 순서가 매겨진다. 첫 번째 트랜잭션이 커밋해 `practice_completions`
행을 만들면, 락을 기다리던 두 번째 트랜잭션은 잠금을 얻은 뒤에야 `findByUserIdAndTutorialKey` 조회를
수행하므로 반드시 "이미 존재함"을 보게 되어 보상을 건너뛴다. 별도의 새 락이나 `SELECT ... FOR UPDATE`
추가가 필요 없다 — 기존 026/031 락 설계를 그대로 재사용한다.

## 컨트롤러 영향 (동기화 대상 여부)

- URL·HTTP 메서드 추가/삭제 없음 → `docs/api-routes.md`의 매핑 표는 변경 불필요.
- 세 엔드포인트의 **응답 의미**가 바뀐다(`restart`가 실제로 재시작함, `holding-reflections` 응답에 새 필드
  가능성, `PUT ensure`의 오류 조건 축소) → **`docs/api-contracts.md`는 구현 커밋에서 갱신이 필요하다.**
  이 계획 단계에서는 "controller가 추가/변경되면 함께 갱신"이라는 CLAUDE.md 규칙 7 대상임을 표시만 한다.
- 새 엔드포인트가 생기지 않으므로 별도 controller 클래스 추가는 없다. `PracticeAttemptRestartController`,
  `PracticeAttemptController`, `PracticeHoldingReflectionController`(정확한 클래스명은 구현 시 확인) 자체의
  매핑 시그니처 변경도 없다 — 내부적으로 위임하는 service의 동작만 바뀐다.

## 결정됨 (2026-08-16 사용자 확인)

- **`rewardGranted` 응답 필드**: 채택. 프론트엔드가 재완료 성공과 최초 완료 성공을 구분할 수 있도록
  `PracticeHoldingReflectionResponse`에 추가한다.
- **재완료 시 `answer` 텍스트 폐기**: 채택(spec 기본값). 최초 완료의 답변만 `practice_market_reflections`에
  영구 보존하고, 재완료 시 입력값은 evidence 검증에만 쓰고 저장하지 않는다. 이력 저장이 필요해지면 별도
  spec으로 다룬다(`practice_market_reflections`의 `UNIQUE(user_id, tutorial_key)` 제약 변경이 필요해 범위가
  커짐).

## 테스트 계획

- 단위: `PracticeAttempt.restart()`가 `COMPLETED`에서도 정상 전이하는지, `createAttemptReflection`의
  최초/재완료 분기(보상 지급 여부, 저장 호출 여부)를 Mockito로 검증.
- 슬라이스: `PracticeAttemptRestartControllerTest`에 완료 attempt 재시작 200 회귀 케이스 추가.
  `PracticeAttemptControllerTest`에 완료 evidence + 진행 중 attempt 조합의 정상 응답 케이스 추가.
- 통합(Testcontainers): STOCK 한 시장 기준 (1) 최초 완료 → 보상 지급 확인 → 재시작 → 재완료 → 계좌 현금
  불변 확인 → `practice_completions`/`practice_market_reflections`/`practice_progresses` row count 불변
  확인. (2) 배포 이전 완료를 흉내 낸 기존 `practice_completions`/`practice_progresses` 데이터를 직접
  seed한 뒤(마이그레이션 없이 기존 스키마 그대로) attempt만 재시작·재완료해 보상 미지급을 검증 — 이것이
  "백필 없이 정확한 소급 판정"의 근거 테스트다. (3) 두 스레드/두 트랜잭션으로 재완료 요청을 동시에 실행해
  총 보상 지급 0회를 검증(동시성).
- 회귀: `026`/`031`의 최초 완료·보상 지급 흐름, `039`의 미완료 attempt 재시작(정리·순체결수량 보상매도)이
  이번 변경으로 깨지지 않는지 기존 테스트 스위트로 확인.
