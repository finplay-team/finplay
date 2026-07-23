# ADR-0006: Java 17 전환과 프로젝트명 FinPlay 변경

- 상태: 승인됨
- 날짜: 2026-07-23
- 대체: [ADR-0002](0002-architecture.md)의 스택 항목 중 Java 버전과 패키지명 표기 (레이어드 아키텍처·도메인 패키지 구조 결정은 그대로 유효)

## 맥락

2026-07-23 PRD(`docs/prd.md`) 확정 과정에서 사용자가 두 가지를 결정했다.

1. PRD 기술 계획이 Java 17을 명시하고, 레포는 Java 21로 생성돼 있어 충돌했다. Java 17로 통일하기로 결정.
2. 제품명이 TradeClass에서 **FinPlay**로 변경됐다.

## 결정

- **Java 17** (toolchain `JavaLanguageVersion.of(17)`). Spring Boot 4.1의 최소 지원 버전이므로 호환 문제 없음.
- **프로젝트명 FinPlay**로 통일.
  - 패키지: `com.tradeclass.api` → `com.finplay.api`
  - Gradle: `rootProject.name = "finplay-api"`, `group = "com.finplay"`
  - 로컬 DB명: `tradeclass` → `finplay` (compose.yaml)
  - 진입점: `FinPlayApiApplication`

## 결과

- ADR-0002의 본문에 남은 `Java 21`, `com.tradeclass.api` 표기는 역사 기록이며, 이 ADR이 우선한다.
- 레포 폴더명(`Desktop\tradeclass-api`)과 GitHub 레포명 변경은 로컬 클론 경로에 영향을 주므로 팀 합의 후 수동으로 진행한다 (미변경 상태여도 코드·문서는 FinPlay 기준).
- 프론트엔드 레포명 변경은 해당 레포에서 별도 진행한다.
