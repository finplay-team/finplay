# Agent 실수 로그

AI 에이전트가 실제로 저지르고 재현·확인된 실수만 기록한다. **추측성 예방 규칙 금지** — 실제 발생하지 않은 것은 적지 않는다. 구현 시작 전 이 파일을 읽고, 같은 실수를 재현·확인하면 행을 추가한다.

| 날짜 | 실수 | 증상 | 수정 | 재발 방지 |
|---|---|---|---|---|
| 2026-07-22 | Spring Boot 4에서 Flyway를 `flyway-core` 의존성으로만 추가 | 에러 없이 마이그레이션이 조용히 스킵됨 (Boot 4 모듈 분리로 자동설정 미적용) | `spring-boot-starter-flyway`로 교체 | Boot 4에서 인프라 의존성은 스타터 존재 여부부터 확인. 마이그레이션 실행은 `flyway_schema_history` 테이블로 검증 |
| 2026-07-22 | 한글 포함 경로에 프로젝트 생성 | 컴파일은 되는데 테스트만 전부 `ClassNotFoundException` (Gradle 테스트 워커가 한글 클래스패스를 못 읽음) | 영문 경로(`Desktop\tradeclass-api`)로 이전 | 프로젝트/클론 경로는 항상 영문. `-Dfile.encoding=UTF-8`로는 해결 안 됨 |
| 2026-07-23 | Windows에서 `gradlew`를 실행 비트 없이(100644) 커밋 | 로컬(Windows)은 전부 정상인데 GitHub 푸시 후 Linux CI가 전 PR에서 `./gradlew: Permission denied` (exit 126) | `git update-index --chmod=+x gradlew` 후 커밋 | Windows는 파일 권한이 없어 git 인덱스 모드로만 관리됨. CI 첫 실행 전 `git ls-files -s gradlew`가 100755인지 확인 |
| 2026-07-23 | Spring Boot 4.1에서 `@WebMvcTest` import를 구 경로(`org.springframework.boot.test.autoconfigure.web.servlet`)로 작성 시도 | 컴파일 실패 또는 슬라이스 미적용 (Boot 4.1이 테스트 자동설정 패키지를 `org.springframework.boot.webmvc.test.autoconfigure`로 이동) | 새 패키지 경로로 import 교체 | `@WebMvcTest` 등 웹 슬라이스 테스트 작성 시 Boot 4.1 새 패키지 경로 사용. `@DataJpaTest` 등 다른 슬라이스도 이동 여부 확인 후 착수 |
