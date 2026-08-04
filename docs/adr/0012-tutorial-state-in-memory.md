# ADR-0012: 투자 실습 튜토리얼의 즐겨찾기·사전 의도 상태는 서버 인메모리에 둔다

- 상태: 승인됨
- 날짜: 2026-08-04

## 맥락

`docs/specs/016-investment-education-policy`의 3단계 투자 실습 튜토리얼은 candidate 1~4에서 `favorites`(V14)와 `practice_intentions`(V16, `practice_progresses`와 같은 migration) 두 테이블을 MySQL에 영속화했다. 조사 결과:

- 두 테이블은 education 도메인 전용이며 다른 도메인이나 진행 중인 PR(#191, #192)이 참조하지 않는다.
- `practice_progresses`("완료 여부"만 담는 진행 상태 요약)는 `practice_completions`와 함께 튜토리얼의 불변 완료 판정 정본이라 영속성이 필요하지만, `favorites`·`practice_intentions`는 튜토리얼 진행 중 상태를 보여주는 임시 데이터에 더 가깝다.
- 팀은 이 두 테이블을 서버 재시작·다중 인스턴스 시 유실 가능한 힙 메모리 저장으로 전환해 스키마·마이그레이션 부담을 줄이기로 사용자와 합의했다.

ADR-0002는 레이어드 아키텍처만 규정하고 모든 도메인 상태를 DB에 영속화하라는 규칙을 두지 않았다. ADR-0004는 "스키마 변경은 Flyway로만 한다"는 규칙이며, 테이블을 DROP하는 것도 스키마 변경이므로 이 결정도 Flyway migration(V19)을 따른다 — ADR-0004와 상충하지 않는다. 따라서 이 문서는 기존 ADR을 대체(supersede)하지 않고 새 결정을 기록한다.

## 결정

- `favorites`, `practice_intentions` 두 테이블을 V19 migration으로 DROP한다. `practice_progresses`, `practice_completions` 등 완료 판정에 관여하는 테이블은 그대로 유지한다.
- `FavoriteService`는 `@Service` 싱글턴 빈 내부에 `ConcurrentHashMap`으로 상태를 관리한다. `PracticeIntentionService`는 저장 구조를 `PracticeIntentionRepository`(`@Repository`, JPA 아님)로 분리하고 `ConcurrentHashMap` 기반 저장을 그 안에 둔다 — 기존 repository 레이어 경계(ADR-0002)를 유지해 저장 방식이 DB에서 메모리로 바뀌어도 서비스가 저장소 구현 세부(맵 구조·락 없음)에 직접 의존하지 않도록 하기 위함이며, 두 도메인 모두 이 인메모리 컴포넌트를 다른 도메인에 노출하지 않는다.
- 기존에 MySQL `PESSIMISTIC_WRITE` 행 잠금으로 보장하던 `favorite → intention` 순서의 직렬화는 **사용자 단위 in-process 잠금**(`ConcurrentHashMap<Long userId, ReentrantLock>` 등)으로 대체한다. `practice_progresses`는 여전히 DB 행이므로 기존 `SELECT ... FOR UPDATE` 잠금을 유지하고, in-memory favorite 잠금은 그 DB 트랜잭션이 열려 있는 동안 이어서 획득한다. 상세 설계는 `docs/specs/016-investment-education-policy/plan.md`.
- 서버 재시작 또는 다중 인스턴스 배포 시 진행 중인 즐겨찾기·사전 의도가 유실될 수 있음을 사용자가 명시적으로 감수했다. 다중 인스턴스 환경에서는 인스턴스별로 상태가 분리되어(sticky session 없이는) 사용자가 같은 튜토리얼 진행 중 다른 인스턴스로 라우팅되면 자신의 즐겨찾기·의도를 못 찾을 수 있다.
- API 계약(엔드포인트 경로, 요청/응답 필드, 오류 코드)은 변경하지 않는다. 내부 구현만 DB에서 메모리로 바뀐다.

## 근거

- 튜토리얼은 실제 자산·금전을 다루지 않는 연습 흐름이라 재시작 시 진행 유실의 비용이 낮다. 반면 완료 여부(`practice_progresses`/`practice_completions`)는 사용자에게 "이미 끝냈다"는 영구적 사실이라 유실 시 사용자가 튜토리얼을 다시 밟아야 하는 문제가 더 크므로 DB에 남긴다.
- 스키마·마이그레이션·Testcontainers 기반 테스트 부담을 줄여 튜토리얼 반복 조정을 빠르게 한다.

## 결과

- `FavoriteRepository`, `PracticeIntentionRepository`(JPA)와 `Favorite`, `PracticeIntention` `@Entity`는 제거하고 순수 Java 모델 + 인메모리 저장 구조로 교체한다.
- `V14__create_favorites.sql`, `V16__create_practice_progresses_and_intentions.sql` 중 `favorites`, `practice_intentions` 테이블 생성분을 V19 `DROP TABLE`로 되돌린다. `practice_progresses` 생성문은 그대로 둔다. ADR-0004에 따라 V14·V16 파일 자체는 수정하지 않는다.
- DB/Testcontainers 기반이던 `FavoriteRepositoryTest`, `FavoriteConcurrencyIntegrationTest`, `PracticeRepositoryTest`(favorites·practice_intentions 관련 부분) 등은 순수 단위 테스트 또는 인메모리 동시성 테스트로 재작성하거나 삭제한다. `docs/adr/0003-testing-strategy.md`의 계층 중 이 두 도메인은 더 이상 `@DataJpaTest` 대상이 아니다.
- 향후 이 상태를 다시 영속화해야 한다면(예: 다중 인스턴스 sticky session 없는 배포로 전환) 새 ADR로 재검토한다.
- **무제한 증가를 감수한다.** `favoritesByUser`는 삭제 시 항목을 지우지만 `locksByUser`의 `ReentrantLock`은 영구히 남고, `PracticeIntentionRepository`의 사용자별 리스트는 append 전용이라 삭제 경로가 없다. 인증 사용자가 반복 요청할수록 힙이 단조 증가한다. 튜토리얼은 로그인 사용자만 접근하고 practice_progresses가 완료 후 재시도를 막아 실질 상한은 있지만, 사용자별 상한·TTL 정리는 별도 이슈로 남긴다.
- **`@Transactional` 롤백이 인메모리 쓰기를 되돌리지 않는다.** `PracticeIntentionService`가 `practice_progresses` DB 트랜잭션 안에서 intention을 메모리에 저장한 뒤 커밋이 실패하면, DB insert는 롤백되지만 메모리에 남은 intention은 되돌아가지 않아 클라이언트는 오류를 받고도 서버에는 의도가 기록된 상태가 될 수 있다. 구조상(DB 트랜잭션과 힙 쓰기는 같은 커밋 단위가 아님) 막기 어려워 감수 대상으로 남긴다.
- **ID 시퀀스가 재시작으로 재사용된다.** `favoriteId`·`intentionId` 모두 프로세스 기동마다 1부터 다시 채번되므로, 재시작 전후로 서로 다른 리소스가 같은 ID를 가질 수 있다. 현재 계약은 ID의 시간적 유일성을 규정하지 않아 문제가 없지만, 이후 다른 기능이 이 ID를 영속 FK로 저장하기 시작하면 재검토가 필요하다.
