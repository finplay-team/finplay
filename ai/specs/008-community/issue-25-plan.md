# Issue #25 커뮤니티 게시물 단건 조회 API 구현 계획

**Goal:** 인증 사용자가 `GET /api/community/posts/{postId}`를 호출하면 제목·본문·작성자·시각을 포함한 게시물 한 건을 200으로 받고, 게시물이 없으면 공통 404 오류를 받는다.

**관련 정본:** GitHub Issue #25, PRD `COM-001`·`COM-003`와 §5 공통 오류표, `spec.md`, ADR-0002, `docs/conventions.md`

**선행:** Issue #23이 merge되어 `community_posts`, `CommunityPost`·Repository·Service·Controller와 6필드 `CommunityPostResponse`가 존재한다. `/api/community/**`는 기존 인증 정책으로 보호된다.

## 요구사항 ID와 수용 기준

- PRD `COM-001`: 게시물 단건 조회를 제공한다.
- PRD `COM-003`: 게시물 조회는 인증 사용자에게만 허용한다.
- PRD §5: 인증 없음·만료는 401 `UNAUTHORIZED`, 대상 없음은 404 `NOT_FOUND` 공통 오류 형식이다.
- 정상 조회는 200이며 기존 `CommunityPostResponse`의 `postId`, `authorNickname`, `title`, `content`, `createdAt`, `updatedAt`을 반환한다.

수용 시나리오는 다음과 같다.

1. 존재하는 `postId`와 유효한 Access Token으로 조회하면 200과 해당 게시물의 6개 필드를 반환한다.
2. 존재하지 않는 `postId`로 조회하면 404 `NOT_FOUND` 공통 오류를 반환한다.
3. 인증 없이 같은 경로를 호출하면 Controller 진입 전 401 `UNAUTHORIZED` 공통 오류를 반환한다.

## 범위와 제외

### 포함

- `GET /api/community/posts/{postId}`
- 기존 `CommunityPostResponse` 재사용
- 게시물과 LAZY 작성자를 한 조회 흐름에서 안전하게 로드
- 조회 전용 `@Transactional(readOnly = true)` 서비스 경계
- Service 단위, Controller MVC 슬라이스, 실제 DB 통합 테스트
- 구현 후 실제 Controller 매핑 기준 `docs/api-routes.md` 동기화

### 제외

- 게시물 목록·작성 변경·수정·삭제
- 댓글 조회·작성·삭제
- 응답 필드 추가 또는 별도 상세 응답 DTO
- 조회수, 좋아요, 신고, 첨부, 검색, 공개 조회
- 엔티티·DB 스키마·Flyway 마이그레이션 변경
- 다른 커뮤니티 API를 위한 선행 구현

## API 계약

| 항목 | 내용 |
|---|---|
| Method / Path | `GET /api/community/posts/{postId}` |
| 인증 | `Authorization: Bearer <유효한 Access JWT>` 필수 |
| 요청 본문 | 없음 |
| 성공 | 200 + 기존 `CommunityPostResponse` |
| 게시물 미존재 | 404 `NOT_FOUND`, 메시지 `대상을 찾을 수 없습니다.` |
| 인증 없음·만료·변조·잘못된 토큰 타입 | 401 `UNAUTHORIZED` 공통 오류 형식 |

경로 변수 `postId`는 Spring/JPA의 `Long`으로 전달한다. 양수 검증이나 잘못된 숫자 형식의 별도 계약은 PRD와 Issue #25에 없으므로 이번 이슈에서 새 정책을 만들지 않는다.

## 설계 결정

### D1. 기존 6필드 응답을 그대로 재사용한다

상세 조회 요구 필드가 Issue #23에서 확정한 `CommunityPostResponse` 여섯 필드에 모두 포함된다. 별도 DTO나 작성자 내부 ID를 추가하지 않고 `CommunityPostResponse.from(post)`를 재사용한다.

### D2. 기존 공통 `NOT_FOUND`를 사용한다

PRD §5와 현재 `ErrorCode`에 404 `NOT_FOUND`가 이미 존재하므로 게시물 전용 오류 코드를 추가하지 않는다. Repository 조회 결과가 비어 있으면 `BusinessException(ErrorCode.NOT_FOUND)`로 변환해 기존 공통 오류 응답을 사용한다.

### D3. 조회 서비스는 read-only이고 DTO 변환까지 트랜잭션 안에서 끝낸다

`CommunityPostService`에 조회 유스케이스를 추가하고 `@Transactional(readOnly = true)`를 적용한다. Repository에서 게시물과 작성자를 함께 로드한 뒤 같은 메서드 안에서 `CommunityPostResponse.from(post)`를 호출한다.

`CommunityPost.author`는 `LAZY`이므로 단순 `findById` 결과를 트랜잭션 밖 Controller에서 변환하지 않는다. Repository에 `@EntityGraph(attributePaths = "author")`를 적용한 ID 조회 메서드(또는 동등한 fetch join)를 두어 작성자 닉네임 접근이 세션 종료나 OSIV 설정에 의존하지 않게 한다. 전역 연관 로딩을 EAGER로 바꾸지 않는다.

### D4. 기존 레이어를 확장한다

- Controller: `@GetMapping("/{postId}")`, 경로 변수 전달, 200 응답
- Service: read-only 조회, 미존재 오류 변환, 응답 DTO 생성
- Repository: 게시물과 작성자를 함께 조회하는 단건 메서드

흐름은 ADR-0002에 따라 `CommunityPostController → CommunityPostService → CommunityPostRepository`를 유지한다.

## 예상 변경 파일

### Production

- `src/main/java/com/finplay/api/community/repository/CommunityPostRepository.java`
- `src/main/java/com/finplay/api/community/service/CommunityPostService.java`
- `src/main/java/com/finplay/api/community/controller/CommunityPostController.java`

기존 `CommunityPostResponse`, 엔티티, 오류 코드는 변경하지 않는 것을 우선한다.

### Test

- `src/test/java/com/finplay/api/community/service/CommunityPostServiceTest.java`
- `src/test/java/com/finplay/api/community/controller/CommunityPostControllerTest.java`
- `src/test/java/com/finplay/api/community/CommunityPostDetailIntegrationTest.java`

### Documentation

- `docs/api-routes.md`
- `docs/specs/008-community/issue-25-tasks.md`

## 테스트 계획

### Service 단위

- 존재하는 게시물을 조회해 기존 6필드 응답으로 변환한다.
- Repository 미조회 결과는 `BusinessException(NOT_FOUND)`로 변환한다.
- Repository의 작성자 포함 조회 메서드를 정확한 `postId`로 호출한다.

### Controller MVC 슬라이스

- 인증 사용자의 정상 요청은 서비스에 `postId`를 전달하고 200 및 6개 응답 필드를 반환한다.
- 서비스의 `NOT_FOUND`는 404 공통 오류 형식으로 반환한다.
- 비로그인 요청은 401이고 서비스를 호출하지 않는다.

### 통합 (`@SpringBootTest` + MySQL Testcontainers)

- 사용자와 게시물을 실제 DB에 저장한 뒤 GET으로 제목·본문·작성자 닉네임·생성/수정시각을 조회한다.
- 영속성 컨텍스트 의존을 숨기지 않도록 저장 후 컨텍스트를 비우고 조회하며, LAZY 작성자가 응답 변환 시 안전하게 로드되는지 검증한다.
- 존재하지 않는 ID는 404 `NOT_FOUND`, 비로그인은 401 `UNAUTHORIZED`임을 검증한다.

## 미확정 사항

- 없음. 응답은 기존 6필드 DTO, 미존재는 PRD 공통 `NOT_FOUND`, 인증 실패는 기존 `UNAUTHORIZED`로 정본과 일치한다.

## 완료 조건

- [ ] 존재하는 게시물 단건 조회가 200과 기존 응답 6개 필드를 반환한다.
- [ ] 존재하지 않는 게시물은 404 `NOT_FOUND` 공통 오류를 반환한다.
- [ ] 비로그인 요청은 401 `UNAUTHORIZED`를 반환한다.
- [ ] 조회 서비스가 `readOnly = true` 트랜잭션을 사용한다.
- [ ] LAZY 작성자가 OSIV나 우연한 영속성 컨텍스트에 의존하지 않고 안전하게 응답으로 변환된다.
- [ ] 목록·수정·삭제·댓글 등 다른 API를 구현하지 않는다.
- [ ] 실제 Controller 매핑을 `docs/api-routes.md`에 동기화한다.
- [ ] 대상 테스트와 `.\gradlew.bat build --no-daemon --max-workers=1`을 새로 실행해 통과한다.
