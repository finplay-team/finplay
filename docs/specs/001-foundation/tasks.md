# Tasks: 프로젝트 기반 잔여 작업

- [x] compose.yaml에 Redis·Kafka 추가 + spring-boot-starter-data-redis 의존성 + Testcontainers Redis 싱글턴 (+ 연결 스모크 통합 테스트) — 이슈 #31
- [x] QueryDSL Gradle 설정 (`compileJava` 통과 확인) — 이슈 #31
- [x] common 오류 체계 — ErrorCode enum(PRD §5 전체) + BusinessException + ErrorResponse + GlobalExceptionHandler + RequestIdFilter (+ @WebMvcTest) — 이슈 #32
- [ ] 주입 가능한 Clock 빈 (Asia/Seoul) + 고정 Clock 테스트 예시 (+ 단위 테스트)
- [ ] 마무리 — `./gradlew build` 전체 통과 + checklist.md 갱신 (전역 예외 핸들러 항목)
