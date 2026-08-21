# ADR-0029: 도메인 패키지와 전역 공통 패키지를 domain/global로 분리한다

- 상태: 구현됨
- 날짜: 2026-08-21
- 관계: [ADR-0002](0002-architecture.md)의 패키지 구조 예시(도메인 패키지가 `com.tradeclass.api.member`처럼
  루트 바로 아래 있고 `common`이 그 옆에 나란히 있는 형태)를 대체(supersede)한다. "레이어드 + 도메인 기준
  패키지"라는 결정 자체와 "다른 도메인 repository 직접 주입 금지" 원칙은 그대로 유효하다.

## 맥락

`com.finplay.api` 바로 아래에 12개 도메인 패키지(account, auth, community, education, favorite,
feedback, journal, market, order, portfolio, ranking, watchlist)와 전역 예외 처리·공통 응답·설정을 담은
`common` 패키지가 구분 없이 나란히 놓여 있었다. 패키지 목록만 봐서는 어느 것이 특정 도메인 로직이고
어느 것이 도메인에 속하지 않는 전역 공통 코드인지 이름만으로 구분되지 않는다.

## 결정

**도메인 패키지는 `com.finplay.api.domain.<도메인>` 아래로, 전역 공통 코드는 `com.finplay.api.global`로
옮긴다.**

- `com.finplay.api.<도메인>` → `com.finplay.api.domain.<도메인>` (12개 도메인 전부)
- `com.finplay.api.common` → `com.finplay.api.global`, 그리고 `global` 안에서도 역할별 하위 패키지로
  나눈다 — 도메인처럼 파일이 계속 늘어나는 자리라 처음부터 평평하게 두지 않는다.
  - `global.exception`: `BusinessException`, `ErrorCode`, `ErrorResponse`, `GlobalExceptionHandler`
  - `global.config`: `ClockConfig`, `QuerydslConfig` (테스트: `TestClock`, `TestClockConfig`)
  - `global.filter`: `RequestIdFilter`
- `FinPlayApiApplication`은 `com.finplay.api` 루트에 그대로 둔다 (진입점이며 어느 도메인에도 속하지 않음).
- 각 도메인 내부 하위 구조(`controller/service/repository/domain/dto` 등)는 그대로 두려 했으나, 엔티티
  패키지명이 원래 `domain`이었던 탓에 `com.finplay.api.domain.<도메인>.domain`처럼 "domain"이 한 경로
  안에 두 번 겹치는 문제가 새로 생겼다. 그래서 **엔티티 패키지명을 `domain`에서 `entity`로 함께
  바꾼다**: `com.finplay.api.domain.<도메인>.domain` → `com.finplay.api.domain.<도메인>.entity`
  (education의 하위 그룹인 `education.marketpractice`·`education.priceruntime`도 동일하게 적용). 그 외
  하위 구조(`controller/service/repository/dto`)는 변경하지 않는다.

새 도메인이나 전역 공통 코드를 추가할 때 판단 기준은 다음과 같다: **특정 비즈니스 도메인에 속하면
`domain.<도메인>`, 여러 도메인이 공유하는 전역 관심사(예외 처리, 공통 응답 포맷, 전역 설정·필터)면
`global`.** 특정 도메인의 하위 관심사(예: `auth/config`, `market/config`처럼 그 도메인에서만 쓰는 설정)는
`global`이 아니라 해당 도메인 패키지 안에 남는다 — `global`은 "여러 도메인이 공유"가 기준이지 "설정
파일이라서"가 기준이 아니다.

## 근거

- 패키지 목록 자체가 "이건 도메인 로직, 이건 전역 공통"을 구분해주면 새로 합류하는 사람이나 에이전트가
  구조만 보고 어디에 코드를 둘지 판단할 수 있다.
- 기계적 이동이라 레이어 구조·클래스 내용은 전혀 바뀌지 않는다 — 패키지 선언과 import, 그리고 JPQL
  `@Query` 문자열 안의 완전정규화 클래스명(예: `new com.finplay.api.order.service.PracticeOrderFillAttributionDto(...)`)만
  갱신됐다.

## 결과

- 자바 소스 1084개 파일의 package 선언·import·JPQL FQN 문자열이 전부 새 경로로 바뀌었다(`git log`의 이
  커밋 diff가 근거).
- `ai/specs/*/plan.md`, `run-log.md` 등 과거 시점 기록 문서에 남아있는 옛 패키지 경로(`com.finplay.api.<도메인>`,
  `com.finplay.api.common`)는 일부러 고치지 않는다 — 그 문서들은 작성 당시 시점의 기록이며, 사후에 고치면
  "그 시점에 실제로 있던 경로"라는 기록 가치가 사라진다. 현재 구조의 정본은 이 ADR과 `CLAUDE.md`다.
- ADR-0002의 패키지 구조 다이어그램은 옛 경로를 보여주므로 그 문서 자체를 고치지 않고 상태 줄에 이
  ADR로 대체됐음을 표시했다(CLAUDE.md 규칙: "ADR은 수정하지 않고 새 번호로 대체").

## 대안과 기각 사유

**아무것도 하지 않는다(현행 유지)**

도메인·전역 공통이 이름만으로 구분되지 않는 문제가 남는다. 사용자가 직접 지적한 불편이고, 기계적
이동이라 리스크 대비 비용이 작아 채택하지 않았다.
