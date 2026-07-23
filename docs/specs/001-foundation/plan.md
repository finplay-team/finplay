# Plan: 프로젝트 기반 잔여 작업

## 관련 문서
- Spec: `./spec.md`
- 관련 ADR: ADR-0002 (레이어드·도메인 패키지), ADR-0003 (테스트 전략), ADR-0004 (Flyway)
- PRD: §5 공통 오류, §7 기술 계획

## API 설계

신규 엔드포인트 없음. 오류 응답 계약만 정의한다.

```json
{ "error": { "code": "INSUFFICIENT_CASH", "message": "현금 잔고가 부족합니다.", "requestId": "..." } }
```

- ErrorCode enum은 PRD §5 공통 오류표 전체를 포함한다 — VALIDATION_ERROR(400), UNAUTHORIZED(401), FORBIDDEN(403), NOT_FOUND(404), DUPLICATE_RESOURCE·INSUFFICIENT_CASH·INSUFFICIENT_QTY·MARKET_CLOSED·PRICE_UNAVAILABLE·JOURNAL_LOCKED·IDEMPOTENCY_CONFLICT(409), UNSUPPORTED_ORDER_TYPE(422).
- 각 코드에 HTTP 상태를 함께 정의해 핸들러가 매핑한다.

## 구성 요소 설계

### common 패키지 (`com.finplay.api.common`)

| 클래스 | 역할 |
|---|---|
| `ErrorCode` (enum) | 코드 + HTTP 상태 + 기본 메시지 |
| `BusinessException` | ErrorCode를 담는 런타임 예외. 도메인 서비스가 던진다 |
| `ErrorResponse` (record) | `{"error":{code,message,requestId}}` 직렬화 |
| `GlobalExceptionHandler` (`@RestControllerAdvice`) | BusinessException, `MethodArgumentNotValidException`→VALIDATION_ERROR, 404, 그 외 500 매핑 |
| `RequestIdFilter` | 요청마다 UUID 생성 → MDC 저장, 응답 헤더 `X-Request-Id` |
| `ClockConfig` | `@Bean Clock` — `Clock.system(ZoneId.of("Asia/Seoul"))`. 테스트는 `Clock.fixed(...)`로 대체 |

- 범용 Manager·Facade 금지 (PRD C-002). 위 목록 외 공통 클래스는 만들지 않는다.

### Redis

- 의존성: `spring-boot-starter-data-redis`.
- compose.yaml에 `redis:7.4` 서비스 추가. 호스트 포트는 기존 사용 포트(3306·13306·13307·6379 로컬 점유 가능성)와 충돌하지 않게 구현 시 확인 후 지정한다. spring-boot-docker-compose가 매핑 포트를 자동 감지하므로 앱 설정은 불필요.
- Testcontainers: `TestcontainersConfiguration`에 Redis 컨테이너를 MySQL과 같은 static 싱글턴으로 추가. 이미지 태그 고정 (`redis:7.4`, `latest` 금지 — ADR-0003 준용).

### Kafka (준비만)

- compose.yaml에 컨테이너 추가하되 앱의 `depends_on`·설정·의존성에서 제외. 1차 업무 코드 미사용 (PRD §7).

### QueryDSL

- Java 17 + Boot 4.1 기준 jakarta 계열 artifact + annotation processor 설정 (Gradle Kotlin DSL).
- 검증은 엔티티가 생기는 002부터 가능하므로, 여기서는 설정 + `compileJava` 통과까지만.

## 데이터 모델

스키마 변경 없음 (마이그레이션 없음).

## 테스트 계획

- 단위: ErrorCode↔HTTP 상태 매핑, Clock 주입 분기 예시.
- 슬라이스: `@WebMvcTest` — 테스트용 컨트롤러로 BusinessException·검증 실패·404가 공통 포맷으로 직렬화되는지, `X-Request-Id`가 응답에 실리는지.
- 통합: Redis Testcontainers 연결 후 값 읽기/쓰기 스모크 1건.
