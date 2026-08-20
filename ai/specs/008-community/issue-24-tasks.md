# Tasks: Issue #24 커뮤니티 게시물 목록 조회 API

- [x] **1. 목록 조회 계약과 응답 모델 고정**
  - `GET /api/community/posts`에 `page` 기본 0·최소 0, `size` 기본 10·범위 1~50 계약을 적용한다.
  - `CommunityPostResponse`를 목록 항목으로 재사용하고 `content`, `page`, `size`, `totalElements`, `totalPages`, `hasNext`를 담는 `CommunityPostListResponse`를 추가한다.
  - 범위 밖 페이지 파라미터는 400 `VALIDATION_ERROR`, 빈 목록과 마지막 페이지 밖 요청은 200과 빈 `content`로 처리한다.

- [x] **2. QueryDSL 최신순 페이지 조회와 Repository 검증**
  - `CommunityPostRepositoryCustom`과 구현체를 추가하고 기존 Repository가 커스텀 계약을 상속하게 한다.
  - 작성자를 fetch join하여 N+1을 방지하고 `(createdAt DESC, id DESC)`로 안정 정렬한 뒤 별도 count 쿼리로 페이지 메타데이터를 구성한다.
  - MySQL Testcontainers 기반 Repository 테스트에서 최신순·동시각 보조 정렬, 페이지 메타데이터와 빈 결과를 검증한다.

- [x] **3. Service·Controller 조회 흐름과 슬라이스 테스트**
  - Service가 요청값으로 `PageRequest`를 만들고 Repository 결과를 목록 응답으로 변환한다.
  - Controller가 인증 주체를 별도로 사용하지 않는 전체 피드 GET 매핑과 페이지 범위 검증을 제공하며 기존 인증 보호를 그대로 적용한다.
  - Service 단위 테스트와 Controller 슬라이스 테스트에서 기본값·명시값 전달, 빈 페이지, 400 검증 오류, Access 인증 실패 401을 검증한다.

- [x] **4. 인증·페이지 경계 핵심 통합 시나리오**
  - `@SpringBootTest` + MySQL Testcontainers에서 여러 게시물을 두 페이지로 조회해 전체 ID에 중복·누락이 없음을 검증한다.
  - 게시물이 없을 때 200과 빈 `content`, 비로그인 요청에 401 `UNAUTHORIZED`를 검증한다.
  - 단건 조회·수정·삭제, 댓글, 검색·정렬 옵션, 새 Flyway 마이그레이션은 이 이슈에 포함하지 않는다.

- [x] **5. API 문서 동기화와 완료 게이트**
  - [x] 실제 Controller 매핑, 인증, 페이지 기본값·상한, 성공 응답 메타데이터와 400·401 계약을 `docs/api-routes.md`에 동기화한다.
  - [x] `spotlessApply`와 Repository·Service·Controller·통합 대상 테스트를 현재 HEAD에서 실행해 통과한다.
  - [x] 테스트 격리 수정 뒤 결합 실행으로 오염 재현 시나리오가 통과함을 확인한다.
  - [x] `build --no-daemon --max-workers=1`과 `git diff --check`를 실행해 통과한다.
