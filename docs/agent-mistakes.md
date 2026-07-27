# Agent 실수 로그

AI 에이전트가 실제로 저지르고 재현·확인된 실수만 기록한다. **추측성 예방 규칙 금지** — 실제 발생하지 않은 것은 적지 않는다. 구현 시작 전 이 파일을 읽고, 같은 실수를 재현·확인하면 행을 추가한다.

| 날짜 | 실수 | 증상 | 수정 | 재발 방지 |
|---|---|---|---|---|
| 2026-07-22 | Spring Boot 4에서 Flyway를 `flyway-core` 의존성으로만 추가 | 에러 없이 마이그레이션이 조용히 스킵됨 (Boot 4 모듈 분리로 자동설정 미적용) | `spring-boot-starter-flyway`로 교체 | Boot 4에서 인프라 의존성은 스타터 존재 여부부터 확인. 마이그레이션 실행은 `flyway_schema_history` 테이블로 검증 |
| 2026-07-22 | 한글 포함 경로에 프로젝트 생성 | 컴파일은 되는데 테스트만 전부 `ClassNotFoundException` (Gradle 테스트 워커가 한글 클래스패스를 못 읽음) | 영문 경로(`Desktop\tradeclass-api`)로 이전 | 프로젝트/클론 경로는 항상 영문. `-Dfile.encoding=UTF-8`로는 해결 안 됨 |
| 2026-07-23 | Windows에서 `gradlew`를 실행 비트 없이(100644) 커밋 | 로컬(Windows)은 전부 정상인데 GitHub 푸시 후 Linux CI가 전 PR에서 `./gradlew: Permission denied` (exit 126) | `git update-index --chmod=+x gradlew` 후 커밋 | Windows는 파일 권한이 없어 git 인덱스 모드로만 관리됨. CI 첫 실행 전 `git ls-files -s gradlew`가 100755인지 확인 |
| 2026-07-23 | Spring Boot 4.1에서 `@WebMvcTest` import를 구 경로(`org.springframework.boot.test.autoconfigure.web.servlet`)로 작성 시도 | 컴파일 실패 또는 슬라이스 미적용 (Boot 4.1이 테스트 자동설정 패키지를 `org.springframework.boot.webmvc.test.autoconfigure`로 이동) | 새 패키지 경로로 import 교체 | `@WebMvcTest` 등 웹 슬라이스 테스트 작성 시 Boot 4.1 새 패키지 경로 사용. `@DataJpaTest` 등 다른 슬라이스도 이동 여부 확인 후 착수 |
| 2026-07-25 | 같은 worktree에서 implementer와 tester의 Gradle 검증을 겹쳐 실행 | QueryDSL 생성물 `EOFException`, 테스트 결과 `NoSuchFileException`, main class `ClassNotFoundException`이 번갈아 발생 | 모든 에이전트 Gradle 프로세스 종료 후 `--no-daemon --max-workers=1`로 단독 재실행 | 같은 worktree의 compile/test/build는 역할 보고만 기다리지 말고 실행 프로세스 종료까지 확인한 뒤 순차 실행 |
| 2026-07-26 | 셸 호출 제한으로 `build`가 종료된 직후 Gradle 자식 프로세스 종료를 확인하지 않고 같은 명령을 재실행 | 단일-use Gradle 빌드 3개가 같은 `build/test-results`를 동시에 갱신해 테스트 assertion 실패 없이 `in-progress-results-generic.bin` `NoSuchFileException` 발생 | Gradle daemon 로그로 세 빌드의 실행 시간 중첩을 확인하고, 모든 관련 프로세스 종료 후 단일 빌드로 재실행 | 빌드 호출이 timeout이면 실패나 종료로 간주하지 않는다. Java/Gradle 자식 프로세스와 daemon 로그를 확인해 기존 빌드가 끝난 뒤에만 재실행 |
| 2026-07-27 | 이 하네스 환경(bash/PowerShell 모두)에는 `JAVA_HOME`이 비어 있고 `docker` 명령이 PATH에 없음 | `gradlew`가 JDK를 못 찾아 즉시 실패, Testcontainers 기반 테스트는 `DockerClientProviderStrategy`에서 `IllegalStateException`으로 전부 실패 (구현 결함 아님) | `JAVA_HOME=/c/Users/pmsal/.jdks/ms-17.0.20`을 명시적으로 지정해 Gradle 실행. Testcontainers 테스트/`./gradlew build`는 이 환경에서 실행 불가 — 결과 확인은 Docker 있는 환경(사용자 로컬, CI)에 위임 | Gradle 명령 전 `JAVA_HOME`을 항상 명시적으로 지정. Testcontainers 통합 테스트 실패 시 스택트레이스에 `DockerClientProviderStrategy`가 있으면 코드 문제로 오판하지 말고 Docker 가용성부터 확인 |
| 2026-07-27 | 문서에 남은 오래된 `JAVA_HOME` 경로 `C:\Users\PMS\.jdks\ms-17.0.20`을 사용 | 유효하지 않은 디렉터리로 인해 Gradle 실행 전에 wrapper가 경로를 찾지 못하고 실패 | 설치된 JDK를 확인해 실제 경로 `C:\Users\PMS\.jdks\corretto-17.0.18`을 사용 | `JAVA_HOME` 설정 전 설치된 JDK 경로와 Java 설정을 확인하고, 오래된 장비별 경로를 하드코딩하지 않는다 |
