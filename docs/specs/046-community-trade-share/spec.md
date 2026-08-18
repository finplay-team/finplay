# Spec: 커뮤니티 매매 내역 공유(네이티브 수익 인증 카드)

## 개요

토스증권 벤치마킹 세션에서 만든 "수익 인증 카드"(PR finplay-frontend#64, 머지 완료)는 캔버스로 그린 정적 이미지를 게시물에 사진처럼 첨부하는 방식이었다. 사용자가 이걸 보고 "네이티브로 구조화된 카드"(토스처럼 게시물 안에 실제 매매 데이터가 박힌 카드)를 원한다고 확정했다. 이 spec은 그 재설계를 다룬다.

**경량 경로로 진행한다** — `/feature` 풀 루프 대신 메인 세션이 직접 구현한다(사용자 지시, 다른 세션이 PR #442 동시성 버그를 고치는 중이라 그 작업과 안 겹치는 새 워크트리·spec 번호로 진행). 그래도 스키마 변경이 있어 최소 문서화는 남긴다.

**우선순위: 코인 먼저, 그다음 주식.** 서비스 주력이 코인이다(사용자 확정).

## 사용자 시나리오

- 사용자가 코인(또는 주식) 종목을 매도해 체결이 완료되면, 그 체결을 커뮤니티 게시물에 "매매 카드"로 첨부해 공유할 수 있다.
- 게시물을 보는 다른 사용자는 사진이 아니라, 종목명·매수가·매도가·수량·수익률·손익 금액이 담긴 구조화된 카드를 본다.
- 본인이 실제로 실행한 매도 체결만 카드로 붙일 수 있다 — 다른 사람의 체결이나 존재하지 않는 tradeId는 거부된다.

## 요구사항

- [x] TRADESHARE-001: `POST /api/community/posts`가 선택적 `sharedTradeId`를 받는다. 값이 있으면 그 tradeId가 (a) 인증 사용자 본인 소유, (b) `side=SELL`인지 검증한다. 아니면 403(타인 소유) 또는 400(매수 체결)으로 거부한다. 존재하지 않는 tradeId는 404.
- [x] TRADESHARE-002: 게시물 응답(`CommunityPostResponse`/`CommunityPostListResponse`)에 `sharedTrade`(nullable) 필드를 추가한다 — `symbol`·`name`·`market`(`STOCK`|`CRYPTO`)·`buyPrice`·`sellPrice`·`quantity`·`realizedPnl`·`returnRate`를 담는다. `sharedTradeId`가 없는 게시물은 `sharedTrade=null`.
- [x] TRADESHARE-003: `buyPrice`·`returnRate` 계산은 기존 `PostSellFeedbackService`가 이미 갖고 있는 FIFO 가중평균 로직을 재사용한다(코인·주식 모두 지원됨, 이슈 #275) — **재계산하지 않는다**(PRD C-004 원칙과 동일). `PostSellFeedbackService`에 뉴스·서술·반사실·집단 비교를 건너뛰는 가벼운 공개 메서드(`getTradeShareSummary` 등, 이름은 구현 시 확정)를 신설해 `community` 도메인이 그것만 호출한다 — 무거운 `getFeedback()` 전체를 부르지 않는다.
- [x] TRADESHARE-004: 이미지 첨부(`imageId`)와 매매 카드(`sharedTradeId`)는 같은 게시물에 동시에 넣을 수 없다(둘 다 지정하면 400) — 한 게시물은 사진 또는 매매 카드 중 하나만 붙인다.

## 데이터 모델

- `community_posts.shared_trade_id`(nullable, `trades.id` FK, 인덱스) 추가. 마이그레이션 `V45__add_shared_trade_id_to_community_posts.sql`(작성 당시 `V42`).
- 원장(trade) 자체는 불변이라 별도 스냅샷 컬럼을 두지 않는다 — 조회 시점에 `PostSellFeedbackService`의 가벼운 메서드로 매번 다시 계산한다(값은 항상 같다, 체결은 안 바뀌므로).

## 비즈니스 규칙

- `sharedTradeId` 소유권·`side=SELL` 검증은 게시물 생성 시 1회만 한다 — 이후 조회 시점에는 재검증하지 않는다(원장은 불변이라 이후에도 유효함이 보장된다).
- 코인·주식 둘 다 지원한다(기존 프론트 화면의 `!isCrypto` 제약은 이 spec 범위에서 제거한다 — 백엔드 API는 이미 코인을 지원하므로 프론트 게이트만 걷어내면 된다).

## 범위 제외

- 게시물 작성 뒤 `sharedTradeId` 수정 — `updatePost`는 이 필드를 받지 않는다(이미지도 마찬가지로 수정 미지원).
- 배지·좋아요와의 연동(예: 매매 카드 게시물에 특별 배지) — 범위 밖.

## 완료 조건

- [x] 본인 매도 체결로 게시물을 만들면 응답에 `sharedTrade`가 정확한 수치로 채워진다(코인·주식 둘 다, 자동 테스트로 확인).
- [x] 타인 체결·매수 체결·존재하지 않는 tradeId로 시도하면 각각 403·400·404.
- [x] `imageId`와 `sharedTradeId`를 동시에 주면 400.
- [x] 기존 이미지 첨부 게시물·매매 카드 없는 일반 게시물 회귀 없음.
