# ADR-0002: 기술 스택과 레이어드 아키텍처

- 상태: 일부 대체됨 — Java 버전·프로젝트명(패키지명)은 [ADR-0006](0006-java17-finplay-rename.md)이 대체 (Java 17, `com.finplay.api`). 아키텍처 결정은 유효.
- 날짜: 2026-07-22

## 맥락

TradeClass 프론트엔드(React)를 위한 백엔드 API 서버가 필요하다. 팀 프로젝트로 진행되며 팀원 다수가 스프링에 익숙하다.

## 결정

- **스택**: Spring Boot 4.1, Java 21 (LTS), Gradle Kotlin DSL, MySQL 8.4 (LTS)
- **아키텍처**: 레이어드 (`controller → service → repository`)
- **패키지 구조**: 도메인 기준 (`com.tradeclass.api.member`, `com.tradeclass.api.trade` 등). 레이어 기준(`controller/`, `service/` 최상위) 구조는 쓰지 않는다.

```
com.tradeclass.api
├── member
│   ├── MemberController.java
│   ├── MemberService.java
│   ├── MemberRepository.java
│   ├── Member.java            # 엔티티
│   └── dto/
└── common                     # 전역 예외 처리, 공통 응답 등
```

## 근거

- 헥사고날/클린 아키텍처는 이 규모의 팀 프로젝트에 과하다. 레이어드가 팀원 전원이 이해하는 구조다.
- 도메인 패키지 구조는 기능 단위 작업(스펙 단위)과 폴더가 일치해 충돌이 적다.

## 결과

- 도메인 간 참조는 service 레이어를 통해서만 한다. 다른 도메인의 repository를 직접 주입하지 않는다.
- 규모가 커져 모듈 분리가 필요해지면 새 ADR로 결정한다.
