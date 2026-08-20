# Tasks: Issue #25 커뮤니티 게시물 단건 조회 API

- [x] **1. 작성자 포함 단건 조회와 read-only Service 구현**
  - Repository에 ID로 게시물과 LAZY 작성자를 함께 로드하는 `@EntityGraph` 또는 동등한 fetch join 조회를 추가한다.
  - Service에 `@Transactional(readOnly = true)` 조회 메서드를 추가하고 트랜잭션 안에서 기존 `CommunityPostResponse`로 변환한다.
  - 게시물 미존재는 기존 공통 `BusinessException(ErrorCode.NOT_FOUND)`로 처리하며 별도 오류 코드나 DTO를 만들지 않는다.

- [x] **2. GET Controller와 Service/MVC 테스트 구현**
  - `GET /api/community/posts/{postId}`를 기존 Controller에 추가하고 정상 200을 반환한다.
  - Service 단위 테스트로 정상 6필드 변환, 작성자 포함 조회 호출, 미존재 `NOT_FOUND`를 검증한다.
  - `@WebMvcTest`로 정상 200/6필드, 404 공통 오류, 비로그인 401 및 인증 실패 시 서비스 미호출을 검증한다.

- [x] **3. 실제 DB 상세 조회 통합 테스트**
  - [x] `@SpringBootTest` + MySQL Testcontainers에서 사용자와 게시물을 저장한 뒤 상세 조회 결과의 제목·본문·작성자·생성/수정시각을 검증한다.
  - [x] 저장 후 영속성 컨텍스트를 비워 LAZY 작성자 로딩이 OSIV나 1차 캐시에 우연히 의존하지 않음을 검증한다.
  - [x] 존재하지 않는 ID의 404 `NOT_FOUND`와 비로그인 401 `UNAUTHORIZED`를 검증한다.

- [x] **4. API 문서 동기화와 완료 게이트**
  - [x] 실제 Controller 매핑을 기준으로 `ai/api-routes.md`에 GET 경로, 인증, 200 응답 6필드, 401·404 계약을 추가한다.
  - [x] 목록·수정·삭제·댓글 등 다른 API와 엔티티·스키마를 변경하지 않았는지 확인한다.
  - [x] `spotlessApply`, 대상 테스트, `.\gradlew.bat build --no-daemon --max-workers=1`, `git diff --check`를 실행하고 결과를 기록한다.
