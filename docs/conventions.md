# 코드 & 팀 컨벤션

## 코드 컨벤션

### 패키지 구조

- 최상위는 도메인 기준 (ADR-0002 — 레이어 기준 최상위 구조 금지). 도메인 안에서는 계층 하위 패키지로 나눈다.

```
com.finplay.api
├── order
│   ├── controller/OrderController.java
│   ├── service/OrderService.java
│   ├── repository/OrderRepository.java
│   ├── domain/Order.java            # 엔티티
│   └── dto/
│       ├── request/OrderCreateRequest.java
│       └── response/OrderResponse.java
└── common                           # 전역 예외 처리, 오류 응답, 공통 설정만
```

- `common`에는 전역 예외·오류 응답·공통 설정만 둔다. `CommonService`, `Manager`, `Helper` 같은 이름으로 책임을 숨기지 않는다 (PRD C-002).

### 네이밍

- 클래스: 역할을 접미사로 드러낸다 — `OrderController`, `OrderService`, `OrderRepository`. 엔티티는 도메인명 그대로 (`Order`).
- 메서드: controller/service는 행위 기준 (`createOrder`, `getOrder`), repository는 Spring Data 규칙.
- 상수: 매직 넘버·문자열·기간은 `private static final` 필드로 빼고 UPPER_SNAKE_CASE로 짓는다.
- boolean 변수·필드는 `is~`/`has~` (`isDeleted`, `hasStock`), 시간 필드는 `LocalDateTime` + `xxxAt` (`createdAt`, `filledAt`).
- wildcard import(`import foo.*`)를 쓰지 않는다.

### DTO 규칙

- 클래스가 아니라 **record**로 작성한다 (2026-07-23 확정 — 팀 노션의 `@Getter` 클래스 방식 대신 record 유지).
- 위치는 `dto/request/`·`dto/response/` 하위 패키지로 분리한다.
- 용도별 접미사 표.

| 용도 | 접미사 | 예시 |
|---|---|---|
| 생성 요청 | `~CreateRequest` | `OrderCreateRequest` |
| 수정 요청 | `~UpdateRequest` | `JournalUpdateRequest` |
| 단건 응답 | `~Response` | `AccountResponse` |
| 상세 응답 | `~DetailResponse` | `OrderDetailResponse` |
| 목록 응답 / 목록 항목 | `~ListResponse` / `~ListItemResponse` | `TradeListResponse` |
| service 간 내부 전달 | `~Dto` | `OAuthUserDto` — controller와 직접 통신하는 DTO에는 `Dto` 접미사 금지 |

- 요청 DTO 검증 규칙.
  - record 컴포넌트에 Bean Validation 애노테이션을 직접 붙인다 — 필수 `@NotNull`(객체)/`@NotBlank`(문자열), 문자열엔 `@Size(max = N)` 항상 명시, 범위 `@Min`/`@Max`, 형식 `@Email`/`@Pattern`.
  - 필드는 Wrapper 타입(`Long`, `Integer`, `Boolean`)을 쓴다 — primitive는 null 검증이 우회된다.
  - 에러 메시지는 한글 + 마침표로 통일한다 — `"~은/는 필수입니다."`, `"~은/는 최대 N자까지 입력할 수 있습니다."`
- 응답 DTO는 정적 팩토리 `from(entity)`를 둔다 (인자 2개 이상 조합이면 `of(...)`). 엔티티 매핑이 없는 단순 DTO는 생략.

### 레이어 규칙 (3계층 — 스파게티 방지)

요청 흐름은 항상 `controller → service → repository`로 읽혀야 한다.

**Controller가 담당한다** — HTTP 매핑, 요청 DTO 검증(`@Valid`), 인증 사용자·경로·쿼리 값 추출, service 호출, 응답 DTO·상태코드 반환.

**Controller가 하지 않는다** — 비즈니스 판단(잔고 검증 등), 트랜잭션 경계, repository 직접 호출, Redis·외부 API 직접 호출, 엔티티 그대로 반환.

**Service가 담당한다** — 비즈니스 규칙, 트랜잭션 경계(`@Transactional`), 유스케이스 흐름(주문+체결+잔고 갱신 등), 여러 repository 호출 조합. 대상이 없거나 규칙 위반이면 **null 반환이 아니라 즉시 예외**를 던진다 (`orElseThrow(() -> new BusinessException(ErrorCode.~))`).

**Service가 하지 않는다** — HTTP 상태코드·controller 전용 응답 포맷 결정, JPA 쿼리 세부 구현, Redis key 문자열을 여러 곳에서 직접 조립, 모든 기능을 한 서비스에 몰아넣기.

**Repository가 담당한다** — 엔티티 저장·조회, JPA 쿼리 메서드, 필요한 경우 QueryDSL 조회.

**Repository가 하지 않는다** — 비즈니스 판단(현금 부족·주문 가능 여부 등), 외부 인프라 호출, controller DTO 반환.

- 도메인 간 참조는 service 레이어를 통해서만 한다. 다른 도메인의 repository를 직접 주입하지 않는다 (ADR-0002).
- 새 계층·공통화는 문제를 실제로 줄일 때만 추가한다. 공통화는 세 번째 중복이 보이고 책임이 명확할 때만 검토한다.

### Lombok 사용 규칙

쓰는 곳에 맞는 애노테이션만 쓴다. 아래 목록 외(`@Data`, `@Setter`, `@Value`, `@SneakyThrows` 등)는 금지.

| 대상 | 사용 | 이유 |
|---|---|---|
| 엔티티 | `@Getter` + `@NoArgsConstructor(access = AccessLevel.PROTECTED)` + `@Builder` | 빈 상태 생성 차단, 불변 지향. protected 생성자·getter를 손으로 쓰지 않는다 |
| service·controller·component | `@RequiredArgsConstructor` | `final` 필드 생성자 주입. 필드 `@Autowired` 금지 |
| 로깅이 필요한 클래스 | `@Slf4j` | 로거 수동 선언 금지 |
| DTO | 사용 안 함 | record라 Lombok 불필요 |

### Entity 규칙

- 기본 생성자는 protected로 두어 외부에서 빈 상태로 생성하지 못하게 한다.
- 생성은 외부 `new` 호출 금지 — 정적 팩토리 메서드로만 한다. 네이밍은 일반 생성 `create(...)`, 값 조합 `of(...)`. 팩토리 안에서 항상 유효한 상태로 만들어 반환한다 (생성 후 값 채우기 금지).
- setter를 두지 않는다. 상태 변경은 `fill`, `revoke`, `consume`처럼 의도가 드러나는 메서드로만 한다.
- 엔티티는 DTO에 의존하지 않는다 — DTO → 엔티티 변환은 service 책임.
- 엔티티를 controller 밖으로 노출하지 않는다. 응답은 항상 DTO.

### 인프라 연동 위치

| 인프라 | 위치 |
|---|---|
| MySQL/JPA | repository |
| DB 트랜잭션 | service |
| Redis 시세 저장·조회 | market의 전용 component (예: PriceStore) — key 문자열은 이 한 곳에서만 조립 |
| 업비트 WebSocket | market의 전용 client component |

전용 component를 만들더라도 기능 흐름은 service에서 읽히게 유지한다.

### 금지 패턴

- Controller에서 repository를 바로 호출한다.
- 하나의 service가 여러 도메인(계좌·주문·시세·커뮤니티)을 모두 처리한다.
- `CommonService`, `Manager`, `Helper` 같은 이름으로 책임을 숨긴다.
- 엔티티와 API DTO를 같은 목적으로 사용한다.
- 예외 처리가 controller마다 흩어진다 (전역 예외 핸들러 하나로 통일).
- Redis key 문자열이 여러 클래스에 흩어진다.

### API 규칙 (REST)

- URL: 복수 명사, kebab-case (`/api/members`, `/api/trade-orders`)
- 버전 프리픽스를 쓰지 않는다 (`/api/v1/...` 금지 — 2026-07-23 확정. PRD 원본의 `/api/v1` 표기보다 이 규칙이 우선).
- HTTP 메서드 의미를 지킨다 — GET 조회(부수효과 없음), POST 생성·행위, PATCH 부분 수정, DELETE 삭제.
- 상태코드 — 조회·수정 200, 생성 201, 본문 없는 성공 204, 오류는 PRD §5 공통 오류표의 코드·상태를 따른다.
- 에러 응답 포맷 (전역 예외 핸들러에서 통일, PRD 5장 기준):
```json
{ "error": { "code": "MEMBER_NOT_FOUND", "message": "회원을 찾을 수 없습니다.", "requestId": "..." } }
```
- 비즈니스 예외는 `common`의 커스텀 예외 + 에러 코드 enum으로 처리. controller에서 try-catch 금지.

### QueryDSL 사용 기준

- 단순 조회는 Spring Data 쿼리 메서드로 충분하다.
- 동적 조건·커서 페이지네이션·다중 조인 목록 조회에 QueryDSL을 쓴다 (거래내역·일기·게시물 목록 등).
- 위치는 해당 도메인 repository의 custom 구현. service에 쿼리 세부가 새어 나가지 않게 한다.

### 테스트 작성 규칙

- 단위·슬라이스 테스트(`@WebMvcTest`, Mockito 등)는 대상과 같은 기능 패키지에 `XxxTest`.
- Testcontainers 기반 `@SpringBootTest`는 `XxxIntegrationTest` 접미사로 항상 구분한다.
- 테스트 메서드명은 `test` 접두사 없이 "행동 + 기대 결과" 문장형 camelCase — 예: `createOrderFailsWithConflictWhenCashInsufficient`, `signupCreatesTwoAccounts`. 의도가 복잡하면 `@DisplayName` 한글 설명을 병기한다.
- 검증은 AssertJ `assertThat()`을 쓴다. API 테스트는 존재 유무만이 아니라 `jsonPath`로 필드값까지 검증한다.
- 응답 객체를 `mock(XxxResponse.class)`로 만들지 않는다 — 실제 데이터가 담긴 객체를 생성해 stubbing한다.
- 테스트 전략(레벨·비중)은 ADR-0003을 따른다.

### 기타

- 새 소스 파일 첫 줄에 한국어 한 줄 주석으로 역할 명시.
- 포맷은 Spotless가 **NAVER 자바 스타일**(`config/naver-eclipse-formatter.xml`)로 강제한다 (2026-07-23 팀 노션 확정, palantir에서 교체). 커밋 전 `./gradlew spotlessApply`. IDE에 [NAVER IntelliJ formatter](https://naver.github.io/hackday-conventions-java/)를 설정하면 저장 시점부터 일치한다.
- 정적 분석은 `./gradlew build`가 강제한다 — SpotBugs(버그 패턴) + JaCoCo 라인 커버리지 40% 게이트(경량 시작값, 지표 보고 상향). 오탐 제외는 `config/spotbugs/exclude.xml`에 재현 확인된 것만 추가.

### 시크릿

- 비밀번호, API 키, 토큰을 `application*.yml`이나 코드에 커밋하지 않는다. **"로컬 전용 기본값"도 yml에 두지 않는다** (2026-07-24 튜터 피드백) — 환경변수 또는 `.env`(gitignore됨)로만 주입하고, 필요한 변수는 `.env.example`에 이름만 적는다.
- `compose.yaml`의 로컬 개발용 DB 비밀번호는 유일한 예외 (로컬 컨테이너 전용임을 전제).

## 팀 컨벤션

### 브랜치 전략 (dev 통합 브랜치 — 2026-07-23 팀 결정, GitHub Flow에서 변경)
- `main`: 배포·시연용 안정 브랜치. **직접 푸시 금지** (보호 규칙) — `dev`에서 PR로만 머지한다.
- `dev`: 통합 브랜치이자 GitHub 기본 브랜치. 모든 기능 PR의 대상.
- 작업 브랜치: `dev`에서 분기한 `feat/이슈번호-요약`, `fix/이슈번호-요약` (예: `feat/12-member-signup`) → `dev`로 PR.
- 머지 조건: 리뷰 승인 1명 이상. Squash merge. PR 올리기 전 로컬 `./gradlew build` 통과는 작성자 의무 (CI 자동 검증은 배포 단계에서 도입 — 2026-07-24 튜터 피드백).
- 문서·하네스 소규모 작업을 포함한 모든 변경은 `dev` 대상 PR로 병합한다. `dev` 직접 커밋은 허용하지 않는다.

### 커밋 메시지 (Conventional Commits)
```
feat: 회원 가입 API 추가
fix: 주문 조회 시 N+1 문제 수정
docs / test / refactor / chore
```
- 제목은 한국어, 50자 이내. 하나의 커밋 = 하나의 논리적 변경.

### 이슈 & PR
- 작업은 이슈 생성 → 브랜치 → PR 순서. PR에 `Closes #이슈번호` 명시.
- PR은 리뷰 가능한 크기로 (변경 500줄 이하 권장). 커지면 분할.
- 리뷰는 24시간 내 응답을 목표로 한다.
- 리뷰 지적 처리: PR 범위 안이면 **같은 PR에서 수정해 재푸시**(작은 루프), 범위 밖 문제·새 기능이면 **새 이슈로 분리**(큰 루프). 리뷰 코멘트를 새 이슈로 흩뿌리지 않는다.

### 리뷰 체크 질문

PR 리뷰(사람·reviewer 에이전트 공통)에서 다음을 확인한다.

- 요청 흐름이 controller → service → repository 순서로 보이는가?
- 비즈니스 규칙이 controller나 repository에 새어 나갔는가?
- 트랜잭션 경계가 service에 있는가?
- 인프라 세부사항이 도메인 규칙을 읽기 어렵게 만들고 있는가?
- 공통화가 책임을 명확하게 만들었는가, 아니면 숨겼는가?
