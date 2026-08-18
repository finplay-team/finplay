# 📘 FinPlay 통합 PRD — 1차 MVP + 2차 MVP(1차 고도화)

> 상태: **1차 MVP 구현 완료. 2차 MVP(1차 고도화) 진행 중** (최종 갱신 2026-08-10)
>
> 목적: 팀이 Notion에서 확정한 제품 범위를 사람과 AI 구현자가 같은 요구사항으로 읽고, **현재 차수의 범위**를 넘지 않으며, 테스트 근거를 남기면서 구현하도록 만든 저장소 구현 기준 문서다.
>
> **이 문서는 1차 MVP 전용이 아니다.** 최초 반입 시점에는 1차 MVP만 담았으나 이후 2차 MVP(1차 고도화) 요구사항이 차수 표시와 함께 계속 추가됐다 — 지정가(LMT-001~005), 알림(NOTI-001~005, 2026-08-07 3차로 재이동), 투자일기(JOUR-001~006), 랭킹(RANK-001~002), 캔들 기간 확장(MKT-009), 3단계 투자 실습(`docs/specs/016-investment-education-policy`), AI 피드백(`docs/specs/012-ai-feedback`). **§4·§5·§6의 제목에 남은 "1차"는 그 절이 1차 내용만 담는다는 뜻이 아니다** — 각 요구사항 ID와 절마다 붙은 차수 표시를 기준으로 읽는다. 지금 무엇이 실제로 구현됐는지는 **§3의 "구현 현황"** 표를 정본으로 본다.

> **정본 안내 (2026-07-24 개정)**
> - 팀이 확정하는 **제품 범위·API 단계·담당자는 [Notion 10 X TEN](https://www.notion.so/10-X-TEN-d3ab1fddfba9833d99f38105b2295b08)이 정본**이다.
> - 이 파일은 Notion 결정을 구현 가능한 요구사항으로 옮긴 **저장소 구현 스냅샷**이다. 구현 에이전트는 저장소 안에서 이 파일과 `docs/specs/`를 읽되, Notion과 충돌을 발견하면 임의로 한쪽을 선택하지 않고 구현을 멈춘 뒤 문서를 먼저 동기화한다.
> - Notion 원본 대비 반입 시 확정된 변경: ① 제품명 Investory → **FinPlay** (ADR-0006) ② Base URL `/api/v1` → **`/api`** (버저닝 미사용, `docs/conventions.md`) ③ Java 17 확정 (레포 초기값 21에서 변경, ADR-0006).
> - 기능 구현 시 이 문서를 직접 구현 근거로 쓰지 않는다 — 요구사항 ID 단위로 `docs/specs/NNN-*/` spec을 만들어 진행한다 (`docs/specs/README.md`).

## 0. 문서 규칙과 출처

### 출처 우선순위

충돌이 있으면 아래 순서를 따른다.

1. 2026-07-23 사용자 확정 결정
2. 서비스 상세기획서 v2
3. 기존 API 명세서
4. 기존 테이블 명세서

이 문서에서 1차 요구사항 ID가 부여된 내용은 기존 API·테이블 명세보다 우선한다. 2차와 3차 기능을 1차 태스크에 포함하지 않는다.

### 참조 문서

- [Notion 참조 문서 1](https://app.notion.com/p/3a5b1fddfba980a99393c70908d19749), [Notion 참조 문서 2](https://app.notion.com/p/3e6b1fddfba98241938e81c49f063dea) — 기존 API·테이블 명세서
- 정책 문서: `모의투자플랫폼_서비스상세기획_v2.md` (작성자 로컬 보관 — 레포 미포함)
- Spec Kit: <https://github.com/github/spec-kit>
- Superpowers: <https://github.com/obra/superpowers>

### 기존 명세 변경표

| 항목 | 기존 명세 | 이 문서의 1차 결정 |
|---|---|---|
| 투자일기 | 주문 전에 목표가·손절가 필수 | 작성·수정·목록·상세·매도 회고·AI 복기 전부 2차(1차 고도화) 이후, 1차 MVP 범위 아님 (2026-07-28 정정) |
| 내 정보 | `GET /api/me` 조회만 현재 제외 | `GET /api/auth/me` 조회와 재인증 기반 닉네임·이메일 변경을 1차 MVP에 포함 |
| 계좌·보유 조회 경로 | `/api/accounts/{market}` 계열 | Notion MVP 표의 query 경로(`/api/accounts/summary?market=`, `/api/holdings?market=`)로 통일 |
| 주문 유형 | 시장가·지정가 | 시장가만 |
| 지정가 체결 | 이벤트 드리븐 상시 처리 | 2차에서 지정가+상시 처리(이벤트 드리븐, 코인 내부 가격 모델 갱신(빗썸 웹소켓 수신) 시 인메모리 `ApplicationEvent`로 적재 — 랭킹과 동일하게 이벤트 드리븐 스타일을 쓰되, `AFTER_COMMIT`은 가격 수신이 아니라 체결 트랜잭션 자체에 적용) — 배치 체결에서 재변경 (2026-08-03, LMT-002). **2차 지정가는 코인을 우선으로 시작하고 주식은 추후 처리한다**(2026-08-05, LMT-001~004) |
| 거래 비용 | 주식 수수료+매도세, 코인 수수료 | 주식·코인 수수료만, 세금 제외 |
| AI 피드백 | 주문 전·매도 후·D+7·주간 | 1차 제외, 2차 |
| 랭킹 | 실시간 실현손익 | 1차 제외, 2차 — 시장별(STOCK/CRYPTO) 분리, Redis ZSET으로 순위 관리, REST 조회 (**"실시간"은 체결 즉시 score 반영을 뜻하며 클라이언트 push를 뜻하지 않는다** — SSE push는 검토 후 제외, 랭킹은 체결마다 push할 만큼 긴급하지 않고 Notion API 표에도 REST만 등재됨, 2026-08-03, RANK-001·RANK-002) |
| 알림 | 카테고리명만 존재, 세부 범위 없음 | 1차 제외, 3차 — 지정가 매수·매도 체결 시에만 발생한다(시장가는 즉시 응답으로 체결을 확인하므로 제외), 전달 방식은 SSE 실시간 push를 포함한다. 알림 생성은 별도 API가 아니라 지정가 체결 트리거(LMT-002) 내부에서 처리되는 서버 내부 동작이다 (2026-08-04, NOTI-001~005, 이슈 #140). **2026-08-07: 착수 시점을 2차 → 3차로 재조정(이슈 #261), 범위·전달 방식 확정 내용은 변경 없음** |
| 커뮤니티 | 수익 인증·반응·신고 포함 | 일반 게시물 CRUD+댓글 최소 기능 |
| 동시성·Kafka | 체결 이벤트 핵심 경로 | 1차 제외, 2차 고도화 |
| 종목 범위 | 코스피200·코인 상위 30 | 기존 프론트 주식 16종·코인 12종 |

---

## 1. Constitution — 구현 불변 원칙

### C-001 단계 잠금

- 1차 태스크에는 이 문서의 1차 범위만 포함한다.
- 2차·3차 항목을 선행 구현하거나 1차 완료 조건으로 삼지 않는다.
- 새 요구는 기존 태스크에 끼워 넣지 않고 후속 결정으로 기록한다.

### C-002 최소 구현

- 요청되지 않은 Facade, 범용 Manager, 공통 프레임워크를 만들지 않는다.
- 기능별 경계는 유지하되 한 번만 쓰는 추상화는 만들지 않는다.
- 의존성은 요구사항과 검증 방법이 명확할 때만 추가한다.

### C-003 금액과 원장

- 금액·가격·수량 계산에 `double`과 `float`를 사용하지 않는다.
- Java는 `BigDecimal`, MySQL은 명시된 `DECIMAL` 또는 원 단위 `BIGINT`를 사용한다.
- 체결 기록은 수정·삭제하지 않는 불변 원장이다.
- 평가손익은 보유수량과 최신 시세로 조회 시 계산한다.

### C-004 AI 정책 (2026-08-03 뉴스 사용 조항 개정 확정)

- 1차에는 AI 피드백을 구현하지 않는다.
- 2차 이후에도 숫자와 판정은 서버가 계산하고 AI는 관찰형 문장으로만 변환한다.
- 3차 투자 교육 튜토리얼의 코치는 서버가 확정한 교육 자료를 검색 근거로 초보자 수준에서 설명·재서술할 수 있다. 정답·진도·완료·보상 판정에는 참여하지 않고, 검색 근거에 없는 숫자를 만들지 않는다.
- 2차 MVP의 투자 실습 튜토리얼에는 AI·LLM을 사용하지 않는다. 현재 사용자 흐름은 영속 attempt의 샘플 종목 선택·매수 체결·자동 위험 기준(-3% 손절·+5% 익절)·가격 관찰·매도·자유 복기 기록을 서버가 검증해 진행을 판정한다(`docs/specs/039-tutorial-flow-redesign`). 기존 즐겨찾기·사전 의도 API는 legacy 호환으로 유지하되 현재 프론트 흐름의 완료 전제가 아니다. **OCO exit plan은 2026-08-06 결정으로 3차 MVP로 이동했다** — OCO 없이 시장가/지정가 매매로 완결하는 기반 계약은 `docs/specs/026-market-order-practice-tutorial`이고, 샘플 종목의 현재 완료 흐름은 `039`이 부분 대체한다(§3 참고).
- 특정 종목의 매수·매도를 추천하지 않는다.
- 뉴스·공시는 **시간적 동시 발생 서술에만** 사용하고 인과를 단정하지 않는다. 기존 문구는 "외부 시장 정보(뉴스·공시) 미사용"이었으나, 2차 "AI 피드백"의 범위가 "뉴스를 통한 변동 원인 + 수익률 피드백"으로 확정되면서 개정했다 (`docs/specs/012-ai-feedback`). 위 세 줄은 그대로 유지된다 — 변동률·시각·구간 판정은 여전히 서버 몫이고 AI는 서술만 담당한다.
- 뉴스 기사 본문은 저장하지 않는다. **제목·언론사·원문 URL·발행시각만 저장하고 화면에도 그 범위까지만 노출한다.** 본문은 AI 입력으로만 사용한 뒤 폐기하며, 검색 API가 제공하는 요약 스니펫도 노출하지 않는다 (2026-08-03 확정, C-006과 같은 취지).
- 근거가 없으면 서술을 만들지 않는다 — 가격 변동에 대응하는 뉴스·공시가 없으면 그 구간은 카드 없이 둔다.
- **LLM 연동은 Spring AI 추상화 뒤에 둔다 (ADR-0011, 2026-08-03 승인·PR #154 구현 완료).** 서비스 로직은 `NarrativeGenerator`만 알고 어떤 모델·프로바이더인지 모르며, 프로바이더 교체는 `build.gradle` starter 의존성과 `feedback.llm.*` 설정 변경으로 끝난다. 기본 프로바이더는 OpenAI다.
- **LLM 실패는 기능 실패로 이어지지 않는다.** 키가 없거나 호출이 실패·타임아웃이면 서버가 수치로 조립한 템플릿 문장으로 대체하고, 서술의 출처를 `narrative_source`(LLM·TEMPLATE·NONE)로 구분해 남긴다. **어떤 LLM 실패도 주식 개장·주문·체결을 막지 않는다** — AI 서술은 부가 정보이고 매매 원장은 LLM에 의존하지 않는다.

### C-005 검증과 완료 주장

- Mock/Fake 테스트 성공을 실제 OAuth·빗썸 연동 성공으로 표현하지 않는다.
- 실행한 명령, 통과 수준, 실행하지 못한 외부 검증, 남은 위험을 구분해 보고한다.
- 미정 표식과 "적절히 처리", "필요 시 구현" 같은 추상 문구를 1차 요구사항과 태스크에 남기지 않는다.

### C-006 데이터 이용 정책 (2026-07-24 반입, 2026-07-24 문구 개정, 2026-07-28 KIS 전환 반영)

- 과거 데이터는 **한국투자증권(KIS) Open API**로 조회한다. 한국투자증권에 문의한 결과 과거 데이터는 공공데이터로 확인되어, 비상업적 교육 목적의 공개 서비스에서 가공한 과거 데이터를 제3자(일반 회원)에게 표출하는 데 별도 서면 허가가 필요하지 않다.
- 실시간 주식 시세는 제공하지 않는다 — 재생 대상은 항상 과거 거래일 데이터다.
- 수익 창출(광고·구독·데이터 판매 등)을 하지 않는다.
- 회원에게 원본 데이터 파일 다운로드 기능이나 원본을 조회하는 외부 API를 제공하지 않는다.
- KIS 원본 데이터 파일은 공개 저장소(GitHub)에 커밋하지 않는다.
- 데이터 출처를 화면에 표시한다.
- 실제 주문은 전혀 실행하지 않는다 — 모든 주문은 가상 자산을 이용한 모의투자다.

### C-007 주식 시세 공급자와 공개 표출 정책 (2026-07-24 확정, 2026-07-28 KIS 전환 반영)

- 주식 시세에는 성격이 다른 두 데이터가 있다. 둘 다 한국투자증권(KIS) Open API에서 나오지만 문서·코드·화면에서 섞어 부르지 않는다.
  - **KIS 과거 데이터(1분봉)** — KIS Open API로 조회하는, 실제로 거래된 데이터이지만 **과거 데이터**다. 재생 시점의 현재 시장 상황이 아니다. 공공데이터로 확인되어 제3자 표출 제약이 없다 (C-006).
  - **KIS 실시간 시세** — 같은 KIS Open API의 WebSocket으로 받는 **현재 시장의 실제 체결 데이터**다.
- **"KIS API로 실시간 수신이 가능하다"와 "공개 회원에게 실시간 시세를 표출해도 된다"는 별개의 판단이다.** 전자는 기술 가능성, 후자는 계약·허가 문제다. 전자가 참이라고 후자를 가정하지 않는다. 이 구분은 **실시간 시세에만** 적용된다 — 과거 데이터는 이미 공공데이터로 확인되어 별도 판단이 필요 없다.
- **현재 확인된 것은 과거 데이터의 공공데이터 지위와, 개발자 본인의 KIS Open API 사용 가능 여부뿐이다.** 실시간 시세를 제3자에게 표출해도 되는지는 확인된 바 없다 — 팀원·튜터·심사위원도 제3자다.
- 따라서 `KIS_REALTIME`은 **KIS 개인 계정 소유자인 개발자 본인만 접근하는 개인 개발·본인 전용 검증 환경**에서만 사용한다. 다만 **1차 MVP는 실시간 구현체를 만들지 않는다** — 개인 개발 환경용 실시간 수신·집계 구현은 후속(KIS 실시간 틱 집계)으로 미룬다. 이 정책은 그 구현이 도입될 때 적용된다.
- **팀원·튜터·심사위원 대상 시연은 공개 배포와 동일하게 `KIS_HISTORICAL`을 사용한다.** 실시간 표출 허용 여부가 확인되기 전까지 제3자가 보는 화면에 KIS 실시간 시세를 띄우지 않는다.
- `SERVICE_EXPOSURE=PRIVATE`는 "로그인이 필요한 서비스"를 뜻하지 않는다. **KIS 개인 계정 소유자인 개발자 본인만 접근할 수 있는 로컬 또는 접근 통제 환경**을 의미한다.
- `PRIVATE` + `KIS_REALTIME` 환경을 공개 URL이나 여러 사용자가 접근하는 서버로 운영하지 않는다.
- 일반 사용자가 접속하는 공개 배포 환경(`PUBLIC`)의 기본 공급자는 **KIS 과거 데이터 재생(`KIS_HISTORICAL`)**이다. 공공데이터로 확인되었으므로 서면 답변을 기다리지 않고 공개 MVP 배포를 진행한다.
- 공개 환경에서 KIS 실시간 시세로 전환하려면 **한국투자증권의 서면 허용 또는 필요한 계약 완료가 선행되어야 한다** — 이것이 공개 Provider 전환의 Decision Gate다. 현재 이 답변은 받은 적이 없으며, 그 내용을 추측해 문서·코드에 반영하지 않는다.
- 서면 답변에서 **제한 시연 또는 공개 표출이 허용되면 그때 이 Decision Gate를 해제한다.** 허용 범위가 시연까지인지 공개까지인지에 따라 해제 범위도 달라지므로, 답변 내용을 확인한 뒤 다시 판단한다.
- 설정 플래그(`KIS_PUBLIC_DISPLAY_APPROVED`)를 `true`로 바꾸는 것만으로 법적 허가가 생기지 않는다. **정본은 한국투자증권의 서면 허가·계약 확인 결과이며, 플래그는 그 결과를 시스템에 반영하는 수단일 뿐이다.**
- KIS 과거 데이터 수집·재생 구현은 삭제하지 않는다. 실시간 표출이 허용되지 않는 경우의 안전한 대체 수단으로 계속 유지한다.

---

## 2. 제품 개요

FinPlay는 주식과 코인을 가상 자산으로 매매하고, 거래 결과를 확인하며 다른 사용자와 경험을 나누는 교육형 모의투자 플랫폼이다. 1차 MVP의 목표는 회원이 가입해 두 시장의 계좌를 받고, 시세를 보며 시장가로 매매하고, 주문 목록과 실제 체결 결과를 구분해 확인하며, 게시판을 사용할 수 있는 최소 서비스 루프를 완성하는 것이었다 — **완료됐다.**

**2차 MVP(1차 고도화)의 목표는 그 루프 위에 "왜 이렇게 됐는지 돌아보게 하는" 층을 얹는 것이다** — 가격이 왜 움직였는지 뉴스로 설명하고(AI 피드백), 매매를 기록·회고하게 하고(투자일기), 다른 회원과 견주게 하고(랭킹), 매수 체결 진입가에 서버가 제시한 교육용 위험 기준(-3% 손절·+5% 익절)을 확인하고 실제 행동을 복기하는 경험을 시키는 것(투자 실습)이다. 사용자가 직접 손절·익절 계획을 입력하는 legacy 사전 의도 API는 호환 유지하며, 계획 입력을 전제로 한 OCO 교육은 3차 범위다. **재생 방식이라야 성립하는 기능**(모든 회원이 같은 분봉을 보므로 가능한 반사실 시뮬레이션·집단 비교)을 차별점으로 둔다. 진행 상황은 §3 구현 현황을 본다.

### 핵심 사용자

- 실제 자금을 투입하기 전에 주식·코인 매매 흐름을 경험하려는 입문자
- 자신의 매매 계획과 결과를 기록하려는 사용자
- 팀 시연에서 계좌·시세·매매·기록·커뮤니티 흐름을 검증하려는 운영자

### 1차 성공 기준

- 신규 사용자가 한 번의 가입으로 주식·코인 계좌를 각각 받는다.
- 로그인 사용자가 본인 이메일·닉네임을 조회하고, 재인증 후 안전하게 변경할 수 있다.
- 사용자가 주식 또는 코인을 시장가로 사고팔 수 있다.
- 잔고·보유수량·수수료·FIFO 손익이 일관되게 계산된다.
- 주식·코인 계좌를 합산한 전체 포트폴리오를 확인할 수 있다.
- 주문 목록에서 요청한 주문을, 체결내역에서 실제 체결 결과와 수수료·손익을 각각 확인할 수 있다.
- 게시물과 댓글을 작성하고 본인 콘텐츠를 관리할 수 있다.
- 외부 시세 장애가 잘못된 체결로 이어지지 않는다.
- 자동 테스트와 Docker 로컬 환경에서 위 흐름을 반복 검증할 수 있다.

---

## 3. 단계별 로드맵

### 1차 MVP — 이번 구현 범위

- 회원가입·로그인
- 이메일 인증 흐름과 카카오·네이버 OAuth
- 내 정보 조회와 재인증 기반 닉네임·이메일 변경
- 주식·코인 계좌 2개와 계좌별 시드머니 1,000만원
- 주식 KIS Open API 과거 1분봉 재생
- 코인 빗썸 실시간 시세
- 시장가 매수·매도
- 보유자산·평가손익
- 주식·코인 합산 포트폴리오 요약
- 주문 목록과 체결내역
- 주식·코인 수수료
- 일반 게시물과 댓글
- 최소 CI와 첫 배포, 배포 후 스모크 확인

### 차수 용어 대응 (팀 회의 표현 ↔ 이 문서)

팀 논의에서 쓰는 이름과 이 문서의 단계 이름이 다르다. 같은 범위를 가리킨다.

| 팀 회의 표현 | 이 문서 | 시기 |
|---|---|---|
| MVP | 1차 MVP | 1주차 |
| 1차 고도화 | 2차 MVP | 2주차 |
| 2차 고도화 | 3차 MVP | 3주차 |

C-001 단계 잠금은 이 문서의 차수 이름을 기준으로 판정한다.

### 구현 현황 (2026-08-16 기준)

**이 표가 "무엇이 실제로 동작하는가"의 정본이다.** 아래 요구사항 절들은 차수별 계약 정의이므로 그 절에 요구사항이 적혀 있다는 사실이 구현 완료를 뜻하지 않는다. 실제 엔드포인트 목록은 `docs/api-routes.md`, 요청·응답 계약은 `docs/api-contracts.md`가 정본이다.

> **갱신 규약 (CLAUDE.md 규칙 10).** 요구사항 ID의 구현 상태를 바꾸는 PR은 이 표의 해당 행을 **같은 커밋에서** 갱신하고 근거 칸에 그 PR 번호를 적는다. 기능 제공 범위가 그대로인 리팩터링·테스트·버그 수정·문서 변경은 갱신 대상이 아니다. 표에 없는 새 기능은 행을 추가하며, 판정(완료·일부 완료·문서 확정·미착수)과 근거를 함께 적는다 — **근거 없는 판정은 다음 사람이 검증할 수 없다.** 근거는 코드에서 확인 가능한 형태로 적는다(PR 번호, 없으면 부재하는 컨트롤러·enum 값 등).

| 기능 | 요구사항 ID | 상태 | 근거 |
|---|---|---|---|
| 1차 MVP 전체 (인증·계좌·시세·시장가 매매·조회·커뮤니티·배포) | AUTH-001~006, ACCT-001~003, MKT-001~008, ORD-001~006, PORT-001~002, COM-001~003 | **완료** | 1차 태스크 1~10 (`docs/specs/001`~`010`) |
| 캔들 기간 확장 — 일봉·주봉·월봉 | MKT-009 | **완료** | `013-candle-interval`, PR #151. `interval`은 `1m·1d·1w·1M` 4종 |
| 주문 목록 `market` 필수·커서 페이지네이션 | PORT-003 | **완료** | `018-order-list-pagination`, PR #184 |
| 커뮤니티 고도화 — 종목 기준·대댓글·사진 첨부 | COM-004~006 | **완료(COM-004~006)** | `022-community-enhancement`, 이슈 #246(COM-004, PR 생성 후 번호 갱신 필요)·이슈 #247(COM-005, PR 생성 후 번호 갱신 필요)·이슈 #248(COM-006, PR 생성 후 번호 갱신 필요)·이슈 #330(COM-006 후속: 이미지 저장소 S3 전환, PR 생성 후 번호 갱신 필요). 게시물 작성·수정 시 종목 태그(`instrumentId`), `GET /api/community/posts?instrumentId=` 필터, 존재하지 않는/비활성 종목 400 검증(COM-004) 완료. 댓글 대댓글 작성(`parentCommentId`), 부모 밑 중첩 조회(`replies`), 대댓글에 재답글 시도 400, 존재하지 않는/다른 게시물 소속 부모 404, 부모 삭제 시 행 유지·`content`/`authorNickname` 치환(tombstone, 이슈 #277로 CASCADE 하드 삭제에서 전환)·자식 보존(COM-005) 완료. 이미지 업로드(`POST /api/community/posts/images`)·다운로드(`GET .../images/{imageId}/file`), 게시물 생성 시 `imageId` 연결(존재하지 않음 404·타인 소유 403·이미 연결됨 400), 게시물 삭제 시 이미지 DB 행·물리 파일 정리(COM-006) 완료. `prod` 프로필에서 이미지 저장소를 로컬 파일시스템 대신 S3로 전환(이슈 #330) 완료 — 기능 제공 범위는 그대로다 |
| AI 피드백 — LLM 서술 생성·후검증·템플릿 폴백 | ADR-0011 | **완료** | PR #154 (`NarrativeGenerator`, Spring AI) |
| AI 피드백 — 뉴스·공시 수집 | FEED-001 | **완료** | PR #174 |
| AI 피드백 — 변동 원인 카드 | FEED-002~007 계열 | **완료** | PR #185 (`GET /api/instruments/{id}/price-moves`) |
| AI 피드백 — 종목 뉴스 요약·개장 전 브리핑 | FEED-008·FEED-009 | **완료** | PR #194 (`GET .../news`는 FEED-008, `GET /api/market/briefing`은 FEED-009). 두 기능을 한 행에 묶되 **ID를 둘 다 적는다** — `012` spec §Part D와 `api-routes.md`·`api-contracts.md`의 근거 칸이 브리핑을 `FEED-009`로 부르는데, 이 표에 그 ID가 없으면 정본에서 ID로 검색해도 행이 나오지 않는다(이슈 #410) |
| AI 피드백 — 매도 직후 피드백 | FEED-007 | **완료** | `012` plan의 이슈 6 (Issue #208, `GET /api/ai/post-sell/{tradeId}`). 원장 수치·파생 사실·보유 구간 카드·매도 후 흐름·반사실 가격·AI 서술까지. **반사실 `returnRate`와 집단 비교 지표는 아래 FEED-010·011 행이다.** 이슈 #275로 **코인 체결도 400이 아니라 200이 된다** — spec `§FEED-012` 결정 0~4(장 마감 대신 **KST 자정 게이트**, `sameSessionCompleted` 항상 `true`, 199분 초과 보유는 보유 구간 극값을 **일봉 종가**로 내리고 `holdHighBasis`로 정밀도를 알림, `atClose`는 매도일 일봉 종가). 원장 수치·보유 구간 카드는 주식과 같은 계산을 그대로 쓴다 |
| AI 피드백 — 반사실 시뮬레이션·집단 비교 | FEED-010·011 | **완료** | `012` plan의 이슈 7 (PR #216, Closes #212). 반사실 3종 `returnRate` 수수료 재계산, `peerComparison`의 `NO_EVENT`·`INSUFFICIENT_SAMPLE`·`READY` 판정, 장 마감 배치(`PeerStatsBatchService`)의 `price_move_peer_stats` 확정 집계. 이슈 #275로 **코인 체결에도 같은 두 지표가 열린다** — spec `§FEED-012` 결정 3의 코인 확정 배치(`runCryptoPeerStatsBatch`, 매일 **00:05**에 전날 KST 하루치 카드를 집계하고 `service_date`는 카드 `occurred_at`의 날짜), 반사실 `returnRate`는 코인 수수료율 `0.0005`로 재계산 |
| AI 피드백 — 코인 변동 감시 | FEED-005·006 코인 분기 | **완료** | `012` plan의 이슈 8 (PR #236, Closes #225). `CryptoPriceSnapshotService` 스냅샷 기록·`CryptoPriceMoveWatcher` 탐지·카드 확정, `GET /api/instruments/{id}/price-moves` 코인 분기(게이트 없음, 최근 24시간, `originTradeDate=null`). PR #290(Closes #285) — 근거 0건일 때 온디맨드 수집 후 재매칭(ADR-0017)으로 카드 생성 성공률이 오른다 |
| AI 피드백 — 투자일기 반영 매도 회고 | FEED-013 | **완료** | PR [#388](https://github.com/finplay-team/finplay-backend/pull/388) (이슈 #386). spec `§FEED-013` 결정 1~6. **`GET /api/ai/post-sell/{tradeId}`의 서술 내용과 재생성 조건만 바뀐다** — 엔드포인트·요청·응답 필드·상태값이 하나도 늘지 않았고 `narrativeStatus`는 여전히 항상 `READY`다. 그 매도 체결의 매도 회고 일기 1건과 배분된 lot의 매수 회고 `max-buy-journals`건(매수 시각 오름차순·본문 절단)을 프롬프트에 실어 일기가 있으면 6~8문장 네 덩어리 구성으로 쓰고, **일기가 하나도 없으면 3차와 동일하게 동작한다**(결정 1). 재생성은 잠금이 아니라 **조회 시 `journal_fingerprint` 대조**로 열리며 시각 게이트가 없다 — `journal` 도메인의 수정 계약 네 개는 무변경이고 `JOURNAL_LOCKED`도 만들지 않았다(결정 2). **재생성 사유·카운터를 분리**해 `journal_regenerations`(`max-journal-regeneration`)가 흐름·집단 사유의 `regeneration_attempts`(`max-narrative-retry`)를 소모하지 않고 `narrative_finalized`를 일기 판정에 쓰지 않는다(결정 3). `V37__add_journal_columns_to_trade_feedbacks.sql`로 컬럼 2개 추가. **Part A(변동 카드)·C(뉴스 요약)·D(브리핑)는 전 회원 공유 산출물이라 그대로 일기를 읽지 않으며**, 일기 본문은 응답에 실리지 않는다(프론트는 JOUR-005 경로로 읽는다). 계획 대비 실제 대조(목표가·손절가 판정)는 여전히 범위 밖이다 |
| 투자일기 — 매수 회고 작성 | JOUR-001 | **완료** | PR #181 |
| 투자일기 — 매도 회고 작성 | JOUR-003 | **완료** | PR #189 |
| 투자일기 — 매도 회고 수정 | JOUR-004 | **완료** | PR #192 |
| 투자일기 — 매수 회고 수정 | JOUR-002 | **완료** | PR #201 (이슈 #197). 수정 잠금 없음으로 확정 |
| 투자일기 — 목록 조회 | JOUR-006 | **완료** | PR [#213](https://github.com/finplay-team/finplay/pull/213) (이슈 #203). `GET /api/journal`, 매수·매도 병합·커서 페이지네이션. 통합 `journalId` 미노출 |
| 투자일기 — 상세 조회 | JOUR-005 | **완료** | PR [#219](https://github.com/finplay-team/finplay/pull/219) (이슈 #217). `GET /api/journal/buy/{buyTradeId}`·`GET /api/journal/sell/{sellTradeId}` 타입별 경로 분리 |
| 랭킹 — 전체 랭킹 조회 | RANK-001 | **완료** | `014-ranking`, PR #196 (`GET /api/rankings`, Redis ZSET) |
| 랭킹 — 내 랭킹 조회 | RANK-002 | **완료** | `014-ranking`, PR [#234](https://github.com/finplay-team/finplay/pull/234) (`GET /api/rankings/me`, RANK-001의 ZSET·`countStrictlyGreater` 보정 재사용) |
| 투자 실습 — 즐겨찾기 등록·목록·해제 | EDU-PRACTICE-002 | **완료** | PR #165·#171·#173. **ADR-0012로 인메모리 저장** |
| 투자 실습 — 매수 전 사전 의도 기록(legacy 호환) | EDU-PRACTICE-003 일부 | **완료** | PR #176. **ADR-0012로 인메모리 저장**. API는 기존 소비자 호환을 위해 유지하지만 `039`의 현재 사용자 흐름은 호출하지 않으며, 최초 BUY 체결가 기준 -3%/+5% 위험 snapshot을 서버가 자동 생성한다 |
| 투자 실습 — 튜토리얼 전용 합성 시세 | — | **완료** | PR #195 (`GET /api/education/practice/synthetic-prices/{id}`) |
| 투자 실습 — 코인 가상 가격 실행 환경 | COIN-PRICE-RUNTIME-001~012 | **일부 완료** | `030-coin-practice-price-runtime`, 이슈 #314·#318·#319·#320·#321(PR). 세션 생성·조회·next-tick(`expectedTick` 검증, tick 99 `COMPLETED` 전이) API와 결정적 version 1 생성기, 교육 전용 지정가 BUY API(`POST /api/education/practice/limit-orders`, 주문 `practicePriceSessionId` FK, 세션 전용 이벤트 체결, 마지막 tick 잔여 주문 취소·예약 현금 반환), holding 관찰의 buyTrade→order 세션 역추적(3안 — priceruntime의 `PracticePriceObservationService` 파사드가 세션 가격원 소유, 세션 없는 holding은 기존 `PriceQueryService` 경로 유지) 구현 완료. 사용자·세션·일반 주문 격리 통합 검증(#313 재개 포함)은 후속 이슈. 기존 합성 시세는 표시 전용 호환 유지하고 주식은 3차 MVP |
| 투자 실습 — 주식 체결 재생 세션 FK (주식 OCO 선행, 3차 MVP 완성분) | — | **완료** | PR #191 (`trades.stock_replay_session_id`, V20). 이미 완료된 인프라라 차수 재분류와 무관하게 유지 |
| 투자 실습 — OCO exit plan 생성·목록·취소·트리거 | EDU-PRACTICE-005·006·010·013 | **일부 완료** | PR #348 — 일반 리스크관리 OCO(`intentionId` 생략) 생성·취소 production(`ExitPlanController`, `POST`·`DELETE /api/exit-plans`, `021` plan.md "일반 경로 검증 순서"). 이슈 #349 — 가격 트리거·GTC 체결: `ExitPlanTriggerListener`가 `CryptoPriceUpdatedEvent`(코인 실시간 tick)에서 후보를 조회하고 `ExitPlanFillService`가 `holding → plan` 잠금 아래 익절·손절 시장가 청산·반대 조건 자동 취소·`FILLED_TAKE_PROFIT`/`FILLED_STOP_LOSS` 전이를 수행한다(경로 무관, 코인 GTC만 — 주식 세션 만료 분기는 범위 밖). **목록 조회(`GET /api/exit-plans?status=`, `ExitPlanService.list`)도 production**(PR #400, 이슈 #350) — `status` 생략 시 `PENDING` 기본값, 본인 소유 plan을 `ExitPlanResponse`로 반환. `intentionId` 지정 **교육 경로**(EDU-PRACTICE-005·006·010·013 본연의 튜토리얼 OCO) 재접합만 여전히 미착수 — 현재 컨트롤러는 `intentionId`가 오면 400으로 거부한다. 공통 예약 원장은 `015`로 이미 완료. **엔진 정본은 `021`**(스키마·잠금·트리거·목록), 가격 정책은 `019`, 코인 delta는 `020`, chain 검증·세션 만료는 `016`이 소유한다. 3차 MVP 항목이며 경위는 아래 "3차 MVP" 절 참고 |
| 투자 실습 — 진행 조회·가격 관찰·복기 (**OCO 경로 한정**) | EDU-PRACTICE-001·007·011·012 | **미착수** | `exitPlanId` 기반 OCO 경로(`016` candidate 12·13·14)에 한한 판정이다 — `POST /api/education/practice/observations`·`reflections`, `GET /api/education/practice/oco?market=` 모두 컨트롤러 없음. OCO 완료 key는 `INVESTMENT_OCO_PRACTICE_V1|COIN_OCO_PRACTICE_V1`로 holding 기반 완료와 분리한다. **2026-08-06: OCO 3차 이동에 따라 이 항목도 3차 MVP로 이동.** ⚠️ 같은 기능의 `holdingId` 기반 026 경로는 **이미 구현돼 있다** — 아래 "시장가/지정가 매매 기반 완료 경로" 행 참고 |
| 투자 실습 — 코인 튜토리얼 정책 확정 | — | **일부 완료** | `020-coin-practice-tutorial`, PR #223. GTC 수명·세션 없는 잠금 순서는 문서 확정만(OCO production은 3차 MVP로 이동, 2026-08-06). **`COIN_PRACTICE_V1` tutorial key market 분기는 구현 완료**(이슈 #226) — `026` 경로가 이 key로 코인 실습 완료를 실제로 판정한다 |
| OCO 손절·익절 가격·퍼센트 입력 정책 | — | **문서 확정** | `019-exit-price-policy`, PR #200. production 미착수(3차 MVP) |
| 일반 리스크관리 OCO(`intentionId` 없는 손절·익절) 설계 확정 | — | **문서 확정** | `021-general-risk-management-oco` (2026-08-06 브레인스토밍). `intentionId`를 선택 파라미터화해 튜토리얼 OCO를 이 일반 기능의 특수 사례로 흡수하는 통합 엔진을 설계. **생성·취소·트리거·목록 production 착수는 위 "투자 실습 — OCO exit plan 생성·목록·취소·트리거" 행(PR #348 이후 계열) 참고** — 남은 것은 교육 경로 재접합뿐이다 |
| 투자 실습 — 시장가/지정가 매매 기반 완료 경로(OCO 없이) | MKT-PRACTICE-001~012 완료 | **완료** | `026-market-order-practice-tutorial`. 2단계 chain 해석(PR #295), 참조 가격선·evidence A/B 판정(PR #298), 가격 관찰(PR #302), 자유 복기·불변 완료(PR #304)에 이어 `GET /api/education/practice?market=STOCK|CRYPTO` holding 기반 진행 조회(PR #307)까지 완료. OCO 진행 조회는 별도 URL·완료 key를 쓰는 3차 MVP 범위다 |
| 튜토리얼 전용 샘플 종목·항시 시세·매도 단계·5분 제한 (Sandbox 실습 확장) | SANDBOX-001~009 | **완료** | `031-tutorial-sandbox-instruments`, 이슈 #339, PR #341. 시장별 샘플 종목 3개 신설(1번째만 `tradable=true`, `isTutorialSample` 필드, 커뮤니티 태그 제외), `TutorialSampleInstrumentPriceService`로 `PriceQueryService` 3개 메서드 분기(실제 시세 피드·장 시간 무관 항시 유효 가격), 매도 chain 해석 확장(`sellTradeId`·`sellTradeExecutedAt`), `GET /api/education/practice` 샘플 종목 chain 4단계 응답(`AWAITING_SALE`·`EXPIRED` 상태 추가), 5분 만료 판정과 `holding-reflections` 신규 409 `PRACTICE_SANDBOX_TIME_EXPIRED`, Testcontainers 통합 테스트(전체 흐름·만료 재도전·실제 종목 회귀) 완료 |
| 투자 실습 — 튜토리얼 시장별 최초 완료 보상 500만원 | — | **완료** | 이슈 #343. `PracticeHoldingReflectionService.createReflection`이 완료 저장(`progress.complete`)과 같은 트랜잭션에서 `AccountService.getAccountForUpdate` + `Account.addCash(5_000_000L)`으로 해당 시장 계좌에 지급. `practice_completions`의 `UNIQUE(user_id, tutorial_key)` 불변(026)에 결합돼 정확히 1회만 실행되며 별도 지급 이력 테이블 없음. `GET /api/education/practice`의 `InvestmentPracticeResponse.rewardAmount`가 완료 응답에서만 5,000,000, 그 외 null |
| 투자 실습 — 영속 attempt·자동 위험 기준·단일 29+1 라이브 차트·원자 재시작 | TUTORIAL-FLOW-001~012 | **완료(FLOW-005는 040으로 부분 대체)** | `039-tutorial-flow-redesign`, Backend PR #381 / companion frontend PR #30. 사용자·시장별 단일 영속 attempt/run, 미완료 current-run pending 예약 반환·순체결수량 canonical 보상 SELL 원자 재시작, 최초 BUY 진입가 기준 -3%/+5% 위험 snapshot, 순수 chart GET + 3초 tick POST, STOCK·CRYPTO 공통 흐름, 완료 read-only replay 구현. **TUTORIAL-FLOW-005의 "재시작 요청" 부분은 `040-tutorial-restart-after-completion`이 대체했다 — 아래 행 참고** |
| 투자 실습 — 완료 attempt 재시작 허용, 보상은 최초 완료 1회만 | TUTORIAL-RESTART-001~007 | **완료** | `040-tutorial-restart-after-completion`, 이슈 #402. `PracticeAttempt.restart()`의 `COMPLETED` 가드 제거로 `POST .../attempts/{market}/restart`가 완료 attempt도 실제 정리 후 재시작(TUTORIAL-RESTART-001). `PUT .../attempts/{market}`(진입/ensure)는 completion evidence + 진행 중(비완료) attempt 조합을 더 이상 오류로 오판하지 않고 현재 상태를 반환(TUTORIAL-RESTART-002·003). 최초 완료 여부는 기존 `practice_completions`(`UNIQUE(user_id, tutorial_key)`) 행의 존재로만 판정해 배포 이전 완료자도 소급 정확(TUTORIAL-RESTART-004). 재완료는 `practice_completions`·`practice_market_reflections`·`practice_progresses`에 쓰지 않고 `practice_attempts.status`/`completed_at`만 갱신, 보상 미지급(TUTORIAL-RESTART-005). `practice_progresses` `FOR UPDATE` 락으로 동시 재완료 요청 직렬화해 보상 이중 지급 방지(TUTORIAL-RESTART-006). 재완료 요청의 `answer`는 evidence 검증에만 쓰고 영속하지 않음(TUTORIAL-RESTART-007). `PracticeHoldingReflectionResponse`에 `rewardGranted` 필드 추가, 재완료 응답은 `reflectionId=null`. 새 마이그레이션·스키마 변경 없음 |
| 투자 실습 — 이번 실행 매매 결과(체결가·실현손익·수익률·기준선 대비 판정) 노출 | TUTORIAL-FLOW-013 | **완료** | `039-tutorial-flow-redesign`, PR #425. `GET /api/education/practice`의 attempt evidence에 `tradeResult`(`buyPrice`·`sellPrice`·`realizedPnl`·`returnRate`·`sellVerdict`)를 추가해 "이번 실습에서 얼마를 벌었는지"를 서버가 제공한다. 체결가는 현재 run FILLED 체결의 수량 가중평균(scale 8 `HALF_UP`)이라 부분 매도·복수 체결에서도 성립하고, 매수 1건이면 `riskSnapshot.entryPrice`와 같다. 손익·수익률은 매수·매도 수수료가 모두 반영된 원장 값(`trades.realized_pnl`) 기준이며 수익률 식·정밀도는 매도 직후 피드백(spec 012)과 동일하다. `sellVerdict`(`ABOVE_TAKE_PROFIT|BELOW_STOP_LOSS|BETWEEN_LINES`, 양 끝 포함)는 서버 판정이다. 기존 원장으로만 계산해 스키마 변경·마이그레이션 없음 |
| 지정가 주문·상시 체결 | LMT-001~005 | **완료** | LMT-001(생성)·LMT-002(체결 트리거) 완료 — PR #215(`docs/specs/015-limit-order`, 코인 전용). LMT-003(취소) 완료 — PR #220(이슈 #218). LMT-004(미체결 목록조회, `GET /api/orders/pending`) + 계좌·보유 조회 계약 영향(Decision Gate, `reservedCash`/`reservedQuantity` 노출) 완료 — PR #237(이슈 #235). LMT-005(주문 수정, `PATCH /api/orders/{orderId}`) 완료 — PR #240(이슈 #239). 주식 지정가는 추후 처리(2026-08-05 확정) |
| 관심목록 — 등록·조회·해제 | WATCH-001~003 | **완료** | `023-watchlist`, PR #253(이슈 #252). `POST`·`GET`·`DELETE /api/watchlist-items`, MySQL 영속화(V23). PRD 미등재 신규 기능 — 2차 MVP(1차 고도화)로 확정 |
| 지정가 체결 알림 | NOTI-001~005 | **미착수** | `notification` 패키지·테이블 없음. spec 폴더 미생성. **2026-08-07: 착수 시점을 2차 MVP(1차 고도화) → 3차 MVP(2차 고도화)로 재조정(이슈 #261)** |
| 동시성 제어·부하테스트 | — | **미착수** | Kafka·분산락 의존성 없음 |
| 코인 틱 집계와 캐싱 | MKT-010 | **완료** | `027-crypto-tick-candle-cache`(이슈 #242), PR #255. `transaction` 채널 구독 추가, `CryptoCandleStore`(Lua 원자 갱신)·`CachedCryptoCandleProvider`(캐시·위임 병합) 신설. 동시성 테스트(Testcontainers)로 유실 0건 확인, 실측 호출 절감률 100%(캐시 구간 안) |
| 배포 아키텍처 — 관리형 서비스 전환·블루-그린 | 요구사항 ID 없음(1차 태스크 10 배포의 후속 구조 변경) | **일부 완료** | ADR-0020, 이슈 #326, PR #329. **RDS·ElastiCache 전환은 실배포 검증 완료** — `compose.deploy.yaml`에서 mysql·redis 서비스·볼륨 제거, `finplay-db`(MySQL 8.4)·`finplay-cache`(Redis OSS 7.1, 복제본 1+다중 AZ)에 접속해 기동, Flyway 30건 적용·`/actuator/health` `UP` 확인(2026-08-11). ElastiCache는 전송 중 암호화를 켰으므로 `SPRING_DATA_REDIS_SSL_ENABLED=true`가 필수다. **S3 스토리지 전환(`S3FileStorageService`)과 블루-그린 파이프라인(ALB·타깃 그룹·ACM·`compose.bluegreen.yaml`)은 문서 확정만** — 각각 별도 이슈. ADR-0014·0015·0018이 전제해 온 "다중 인스턴스 전환"의 실체를 ADR-0020이 정의한다. **프론트 정적 파일의 서빙 주체는 ADR-0022(이슈 #352, PR #353)로 nginx 동일 오리진에서 S3 독립 배포로 전환 결정됨** — ADR §결정 7의 적용 순서 중 **1번(백엔드: ADR 확정 + CORS·`forward-headers-strategy` 코드·테스트)만 완료**, 2번(프론트 베이스 URL 도입 + S3 게시 + 실측 검증)·3번(nginx 제거 + ALB 타깃 전환)은 미착수. **CD 워크플로우(`.github/workflows/deploy.yml`)는 코드로 구현됨(PR #357) — 첫 실행 미검증.** |
| 코인 변동 카드 확정 SSE push | 요구사항 ID 없음(GitHub 이슈 #286에는 있으나 이 문서에 대응 행이 신설 전까지 없었다) | **완료** | `028-crypto-card-sse-push`, 이슈 #286, ADR-0018. `GET /api/cryptos/stream` 신설(`CryptoPriceSseController`·`CryptoPriceStreamService`) — 코인 snapshot·price·status에 더해 `CryptoPriceMoveWatcher`가 카드 저장 성공 직후 발행하는 `priceMoveCardConfirmed`(카드 id·종목 id만, 본문 없음)를 push. 발행은 Redis pub/sub(`CryptoPriceMoveCardPublisher`→채널 `feedback:price-move:crypto-confirmed`→`CryptoCardPushSubscriber`)로 다중 인스턴스 팬아웃. Redis 장애·구독자 0명·느린 구독자 모두 카드 생성(`price_move_events` 커밋)을 막지 않음(통합 테스트로 확인). 주식 확정 경로(`PriceMoveCardService`)는 이 채널을 호출하지 않아 노출 게이트를 우회하지 않는다. `GET /api/stocks/stream` 기존 계약은 무변경(회귀 테스트로 확인) |
| 코인 시세 표시·체결 stale 기준 분리 | PRICE-STALE-001~005 | **완료(001·002·004는 036에서 되돌려짐)** | `032-price-quote-stale-split`, 이슈 #355, PR #360. 최초 구현: `PriceStatus`에 `STALE` 추가(PRICE-STALE-002), 코인 표시·체결 판정에서 stale 완화(PRICE-STALE-001·004, 코인 SSE snapshot도 물려받음). **2026-08-14 `036-remove-crypto-stale-status`가 001·002·004를 되돌렸다** — `PriceStatus`는 다시 `AVAILABLE`·`UNAVAILABLE` 2값이고, 표시·체결 모두 경과 시간과 무관하게 항상 `AVAILABLE`이다(근거: `036-remove-crypto-stale-status`). PRICE-STALE-003(`CryptoCandleAndPriceIndependenceTest` 기존 두 테스트 회귀 없음)은 이 변경과 무관하게 계속 유효. PRICE-STALE-005(`HoldingValuationService` 등 하위 소비자의 STALE 처리)는 STALE이 더 이상 발생하지 않아 무의미해졌다(코드 변경 없음, 근거: `036-remove-crypto-stale-status`) |
| 코인 시세 신선도 보강(REST 폴링 백업) + 체결 경로 STALE 허용 | PRICE-REST-001~006 | **완료(004는 036에서 무의미해짐)** | `034-crypto-price-rest-backup`, 이슈 #369(PR 생성 후 번호 갱신 필요). `PriceStore`에 관측 시각(`observedAt`)을 체결 시각(`receivedAt`)과 분리 도입하고 `isStale` 판정 기준을 관측 시각으로 전환(PRICE-REST-001). `BithumbRestTickerPoller`를 운영 프로필(`prod \| crypto-real`)까지 확장해 3초 주기로 전 심볼 빗썸 ticker REST를 폴링하고 `recordObservation`으로 관측 시각을 갱신, 폴링 실패·타임아웃은 그 회차만 건너뜀(PRICE-REST-002). 웹소켓 체결 틱은 `receivedAt` 과거틱 가드가 그대로 유지되어 REST 폴링과 무관하게 즉시 반영(PRICE-REST-003, MKT-003 무변경). 연결 끊김이거나 시세를 한 번도 받은 적이 없으면 여전히 409(PRICE-REST-005, fail-closed 잔여선). `spring.task.scheduling.pool.size` 16→17 상향(PRICE-REST-006). **PRICE-REST-004(체결 경로 STALE 허용)는 2026-08-14 `036-remove-crypto-stale-status`로 도달 불가능해져 무의미해졌다** — 표시 판정이 다시는 STALE을 만들지 않으므로 이 위임 구조("stale이어도 체결 허용") 자체가 의미를 잃는다(코드 변경 없음, 근거: `036-remove-crypto-stale-status`) |
| 코인 시세 STALE 판정 완전 제거 | PRICE-NOSTALE-001~004 | **완료** | `036-remove-crypto-stale-status`, 이슈 #379, PR #380. `PriceStatus`를 `AVAILABLE`·`UNAVAILABLE` 2값으로 되돌리고(PRICE-NOSTALE-002, PRICE-STALE-002 원복), `PriceQueryService.getCryptoDisplayPriceQuote`/`getCryptoDisplayPriceQuotes`의 `priceStore.isStale(...)` 기반 삼항 분기를 제거해 연결 유지+수신 이력 있음이면 관측 시각과 무관하게 항상 `AVAILABLE`을 반환(PRICE-NOSTALE-001). `getOrderExecutionPrice`는 표시 판정에 위임하는 기존 구조 그대로 코드 변경 없이 자동으로 항상 `AVAILABLE`만 받음. `HoldingValuationService`·`SyntheticPriceService`·`PracticePriceSessionService`·`PracticeHoldingObservationService`·`CryptoPriceStreamService`(SSE snapshot)는 코드 변경 없이 STALE 분기가 도달 불가능해져 자동으로 정리됨, 회귀 테스트로 확인(PRICE-NOSTALE-003, "-" 깜빡임 해소 포함). 연결 끊김·수신 이력 없음(`UNAVAILABLE`) 판정과 `CryptoCandleAndPriceIndependenceTest` 기존 두 테스트는 회귀 없음. `PriceStore.isStale`·`observedAt`/`receivedAt` 분리·`BithumbRestTickerPoller`(034 REST 폴링 백업)는 코드 변경 없음 — `CryptoPriceSnapshotService`의 변동 카드 재료 신뢰도 게이트가 계속 사용(PRICE-NOSTALE-004) |
| 주식 분봉 수집 배치 안정성 — 다중 인스턴스 중복 방지·샌드박스 제외·당일 재시도 | COLLECT-STAB-001~005 | **완료** | `035-stock-collector-reliability`(spec·plan·tasks), 이슈 #370. COLLECT-STAB-001(Redis 기반 거래일 단위 수집 락, `StockCollectionLock`)·COLLECT-STAB-002(샌드박스 종목 수집 대상 제외, `findByMarketAndTutorialSampleFalseOrderByIdAsc`)·COLLECT-STAB-003(당일 재시도 스케줄러, `retryPendingInstruments`)·COLLECT-STAB-004(부분·전체 실패 로그 가시화)·COLLECT-STAB-005(운영 KIS 호출 간격 `application-prod.yml`) 전부 구현·테스트 완료, `./gradlew build` 통과. PR [#374](https://github.com/finplay-team/finplay-backend/pull/374). `spring.task.scheduling.pool.size`는 이슈 #369(17)와 겹쳐 18로 재조정(재시도 스케줄은 프로필과 무관하게 항상 뜬다). 같은 조사에서 함께 발견된 배치 실행 시각 9시간 불일치 현상은 이 spec의 범위 밖으로 분리했다 — 원인 미규명 상태로 후속 이슈 [#373](https://github.com/finplay-team/finplay-backend/issues/373)로 넘겼다(이 세션이 EC2·AWS 접근 권한이 없어 로컬 코드 확인만으로는 규명 불가, 상세는 `035` plan.md "배경 조사 ④") |
| 주식 시세·차트 마지막 재생 상태 유지 — 주말·개장 전 블랙아웃 제거 | QUOTE-HOLD-001~007 | **완료** | `038-stock-quote-last-known-hold`(spec·plan·tasks), 이슈 #384. `StockReplaySessionRepository`에 폴백 세션 조회 메서드(`findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc`, 서비스 날짜가 오늘보다 이전인 마지막 `READY` 세션, 날짜 상한 없음) 신설. `StockReplayService.getCurrentPrices`가 `marketStatus=CLOSED`이고 오늘 세션 기준 공개 분봉이 없을 때 그 폴백 세션의 마지막 분봉 종가로 `status=AVAILABLE` 응답을 채운다(QUOTE-HOLD-001). `getRevealedCandles`·`getRevealedAggregatedCandles`도 같은 조건에서 폴백 세션의 원본 거래일 하루치 분봉 전체를 반환한다(집계 경로는 그 거래일 컷오프를 자정 직전까지 확장, QUOTE-HOLD-002. 상한 값으로 `LocalTime.MAX`를 그대로 쓰면 소수 초 없는 `candle_time TIME` 컬럼에서 나노초가 반올림돼 캔들이 항상 0건이 되는 함정이 있어 `LocalTime.MAX.withNano(0)`을 쓴다 — 재현·근거는 `docs/agent-mistakes.md` 2026-08-16 행). 오늘 세션은 폴백 대상이 아니고(QUOTE-HOLD-003), `marketStatus=OPEN`에는 폴백이 전혀 동작하지 않으며(QUOTE-HOLD-004, #370 수집 장애를 옛 값으로 가리지 않음), 폴백 시세는 `sessionReady=false`·`replaySession=null`을 유지해 체결 경로에 도달하지 않는다(QUOTE-HOLD-005, 주문은 여전히 409 `MARKET_CLOSED`). 폴백 세션 탐색에 날짜 상한이 없다(QUOTE-HOLD-006). 값이 멈춰 있는 동안 주식 SSE(`/api/stocks/stream`)는 같은 값의 `price` 이벤트를 반복 전송하지 않고 새 구독자는 `snapshot`으로 멈춘 값을 받는다(QUOTE-HOLD-007, 금요일 세션과 토요일 폴백이 같은 세션이라 전환 시점 값이 바뀌지 않는 것에서 자연히 따라 나옴 — 별도 장치 없음). `HoldingValuationService`의 `costBasis` 대체 경로(PR #96)는 코드 변경 없이 발동 조건이 좁혀져 폴백 후보가 있는 주말에는 더 이상 타지 않는다. 단위(`StockReplayServiceTest`·`LocalForcedOpenStockPriceProviderTest`)·슬라이스(`@DataJpaTest`)·Testcontainers 통합 테스트(금요일 마감 → 토요일 조회 → 월요일 09:01 전환 시나리오)로 검증 |
| 커뮤니티 게시물 좋아요·인기순 정렬 | LIKE-001·LIKE-002·SORT-001 | **완료** | `045-community-likes-sort`(spec·plan·tasks, 대응 GitHub 이슈 없음 — spec.md 머리말 참고), PR #442. COM-001이 1차 명시적 제외 범위로 못 박았던 좋아요를 신규 도입한 요구사항 ID — 토스증권 벤치마킹에서 도출, 사용자 확정(2026-08-18). `community_post_likes`(회원×게시물 유니크, 게시물 삭제 시 `ON DELETE CASCADE`)와 `POST`/`DELETE /api/community/posts/{postId}/likes`로 좋아요 표시·취소를 제공한다(멱등 — 신규 생성 201, 재요청 200, 취소 204(좋아요 없어도 204), 본인 게시물도 허용, 존재하지 않는 게시물 404, LIKE-001). 게시물 목록·단건 조회 응답에 `likeCount`·`likedByMe` 필드를 추가했다(목록은 postId 배치 조회로 N+1 방지, LIKE-002). `GET /api/community/posts?sort=`에 `latest`(기본값, 생략 시 기존과 동일 — 하위 호환)·`popular`(좋아요 내림차순, 동률은 최신순) 정렬을 추가하고 종목 필터(`instrumentId`, COM-004)와 함께 사용 가능하며 그 외 값은 400 `VALIDATION_ERROR`(SORT-001). `community_posts.like_count`는 원자적 `UPDATE`로 증감하는 비정규화 카운터이며, 좋아요 표시·취소는 게시물 행 비관적 락(`SELECT ... FOR UPDATE`)을 트랜잭션 첫 문장으로 잡아 직렬화한다(PR #442 리뷰에서 동시 요청 데드락 500·`like_count` 음수를 실측 재현해 "락 없이 간다"던 초기 판단을 뒤집었다. 감소 쿼리에는 `like_count > 0` 하한 가드도 둔다). `044-community-badges`(PR #439, 미병합)의 "배웠어요" 반응과는 저장 테이블·본인 게시물 허용 여부가 달라 별개로 분리했다 |

### 2차 MVP — 남은 범위와 계약 정의

- 투자 실습 튜토리얼 — 즐겨찾기 등록·목록·해제(EDU-PRACTICE-002), 매수 전 사전 의도 기록(EDU-PRACTICE-003 일부), 표시 전용 합성 시세, 주식 체결 재생 세션 FK의 기존 API·인프라는 2차 MVP 완료인 채 **legacy 호환**으로 유지된다(PR #165·#171·#173·#176·#191·#195). 현재 사용자 흐름의 정본은 `docs/specs/039-tutorial-flow-redesign`이다(Backend PR #381 / companion frontend PR #30) — 영속 attempt에서 샘플 종목을 선택하고, 최초 BUY 체결가 기준 -3%/+5% 위험 snapshot을 서버가 자동 생성하며, 하나의 29+1 차트와 명시적 3초 tick으로 매수·관찰·매도·복기를 연결한다. 미완료 재시작은 current run만 원자 정리하고 완료 사용자는 기록·보상을 초기화하지 않는 read-only replay를 본다. `026`은 OCO 없는 holding 기반 완료 원칙, `031`은 샘플 종목·5분 매도 조건, `030`은 코인 가격 세션의 legacy/병행 계약으로 유지된다. **사용자 입력 OCO exit plan 기반 교육은 3차 MVP**이며 `016`·`019`·`020`·`021`이 정본이다.
- 동시성 제어 — **미착수**
- 부하테스트 — **미착수**
- AI 피드백 — 뉴스 기반 변동 원인 카드, 매도 직후 피드백, 종목 뉴스 요약, 개장 전 브리핑, 반사실 시뮬레이션·집단 비교, 코인 실시간 변동 감시 (`docs/specs/012-ai-feedback`) — **완료** (이슈 8개 전부 머지, 근거는 §3 "구현 현황" 각 행). 뉴스·공시 사용은 C-004 개정으로 허용된다. **종목 뉴스 요약은 3차 → 2차로 앞당겼다** — 변동 원인 카드가 수집 파이프라인을 이미 만들어 3차까지 미루면 같은 코드를 두 번 건드리게 된다
  - **LLM 연동은 Spring AI 추상화를 거친다 (ADR-0011, 구현 완료 PR #154)** — 서비스 로직은 `NarrativeGenerator`만 알고 어떤 모델·프로바이더인지 모른다. 기본 프로바이더는 OpenAI(팀 크레딧)이고 Spring AI는 2.0.0 이상을 쓴다(1.x는 Boot 3.x 전용). **키가 없거나 호출이 실패·타임아웃이면 서버가 수치로 조립한 템플릿 문장으로 대체하며, 어떤 LLM 실패도 주식 개장·주문·체결을 막지 않는다.**
  - **반사실 시뮬레이션과 집단 비교를 2차 범위에 추가한다 (2026-08-04)** — 매도 회고에 "다른 시점에 팔았다면"의 수익률 3종과 "같은 구간을 겪은 다른 회원의 행동 분포"를 붙인다(FEED-010·011). 둘 다 **재생 방식이라야 성립하는 기능**이라 차별점이 되고, 매도 회고 응답에 필드를 더하는 형태여서 새 엔드포인트가 생기지 않는다. 집단 비교는 집계 테이블 1개(`price_move_peer_stats`)를 추가한다
  - **매도 회고 계열(매도 직후 피드백·반사실·집단 비교)은 2차에서 주식 전용이다 (2026-08-04)** — 장 마감·종가·확정 집계 시점이 전부 재생 시간축에 묶여 있어 24시간 거래인 코인에는 대응 개념이 없다. 코인 매도 회고는 3차로 미룬다. **→ 3차에서 열었다 (2026-08-09, 이슈 #275)** — 대응 개념을 새로 정의해 풀었다(spec `§FEED-012`: 장 마감 대신 **KST 자정** 게이트, 마지막 분봉 대신 **일봉 종가**, 199분 초과 보유는 일봉 표본으로 내려 `holdHighBasis`로 정밀도를 알리고, 확정 집계는 **매일 00:05** 배치가 전날 하루치를 집계). 판정과 근거는 §3 "구현 현황"의 FEED-007·FEED-010·011 행이다
- 실시간 실현손익 랭킹 — "실시간"은 체결 즉시 score 반영을 뜻하며 클라이언트 push를 뜻하지 않음 (RANK-001~002, 시장별(STOCK/CRYPTO) 분리 집계, Redis ZSET으로 순위 관리, REST 조회 — SSE push는 검토 후 제외, 2026-08-03) (`docs/specs/014-ranking`) — **완료**: RANK-001 전체 랭킹 완료(PR #196), RANK-002 내 랭킹 완료(PR #234, `GET /api/rankings/me`). **RANK-002는 응답 형태(닉네임 노출·매도 이력 없는 사용자 처리)를 RANK-001의 기존 구현 관례를 그대로 따르기로 확정했다**(2026-08-05, 아래 RANK-002 참고)
- ~~지정가 체결 알림 — 지정가 매수·매도 체결 시에만 발생(시장가는 즉시 응답으로 확인되므로 제외), SSE 실시간 push 포함 (NOTI-001~005, 2026-08-04, 이슈 #140)~~ → **3차로 이동** (2026-08-07, 이슈 #261 — 세부 내용은 §3 "3차 MVP" 절 참고). **미착수**
- 지정가 주문과 상시 체결 (LMT-001~005, 2026-08-03 이벤트 드리븐 상시 처리로 재변경 — 배치 아님) — **완료**: LMT-001~004(생성·체결 트리거·취소·미체결 목록) 완료, LMT-005(주문 수정, `PATCH /api/orders/{orderId}`)도 완료(PR #240, 이슈 #239). **2차 범위는 코인을 우선으로 시작하고 주식은 추후 처리한다**(2026-08-05 확정 — 주식은 재생 데이터 기반이라 "이 가격 도달 시" 조건 자체가 성립하기 어렵고, 분봉 판정 해상도·재생세션 자동취소·OCO와의 잠금 순서 공유 같은 부가 복잡도가 있다). 동시 체결 경합과 취소(LMT-003)·체결 트리거 동시 도착 경합은 비관적 락(SELECT FOR UPDATE, 잠금 순서 `order → account → holding` — 상세는 §LMT-002)으로 제어하기로 확정했다(2026-08-05, 잠금 순서는 2026-08-05 `docs/specs/015-limit-order` 구현·검증 중 정정)
- 매수·매도 회고 작성·수정·상세·목록 (JOUR-001~006, 2026-07-28 Notion 확인 — 작성도 2차로 이동) — **일부 완료**: JOUR-001 매수 작성(PR #181)·JOUR-003 매도 작성(PR #189)·JOUR-004 매도 수정(PR #192)·JOUR-002 매수 수정(PR #201)·JOUR-006 목록(PR #213) 완료, JOUR-005 상세는 계약 확정(이슈 #217) 후 착수 예정
- 캔들 조회 기간 확장 — 일봉/주봉/월봉 (MKT-009) — **완료** (`docs/specs/013-candle-interval`, PR #151)
- 코인 틱 집계와 캐싱 (MKT-010, 2026-08-06 튜터 피드백 계기로 착수 결정, MKT-008의 "틱 미집계·Redis 미저장" 결정을 뒤집음) — **완료**(`027-crypto-tick-candle-cache`, 이슈 #242). 코인 캔들(MKT-008)의 진행 중 1분봉을 빗썸 REST 재조회 대신 서버가 `transaction` 채널 유입으로 직접 만들어 Redis에 캐싱한다. 새 MySQL 테이블은 만들지 않았다

### 3차 MVP — 2차 완료 후 별도 Spec

- **3단계 투자 실습 튜토리얼 OCO 완성 + 일반 리스크관리 OCO 신설 (2026-08-06, 2차 → 3차로 재이동)** — 이 항목이 차수 재분류 경위의 **정본**이며 다른 절은 여기를 참조한다. 2026-08-05에 코인(GTC) 경로를 2차 MVP 활성 트랙으로 우선 구현하기로 했던 결정을 철회한다. OCO exit plan(생성·목록·취소·트리거)과 그 위에 얹히는 튜토리얼 진행조회·관찰·복기는 주식·코인 구분 없이 전부 3차 MVP에서 함께 착수한다 — 튜토리얼 OCO와 일반 리스크관리 OCO(원래 3차 후보)를 처음부터 하나의 엔진으로 설계하기 위함이며 `docs/specs/021-general-risk-management-oco`가 그 통합 설계의 정본이다.
  - **이 재분류는 2차 MVP의 튜토리얼을 미완성으로 두지 않는다** — OCO 없이 시장가/지정가 매매 결과만으로 3단계를 완결하는 `docs/specs/026-market-order-practice-tutorial`을 2026-08-10에 신설해 2차 활성 경로로 삼았고, 진행 조회를 제외한 전 구간이 구현됐다(§3 구현 현황 참고).
  - 주식 경로는 체결 재생 세션 귀속(`buyTrade.stockReplaySessionId`와 `OPEN` session 일치·15:30 이전 생성)과 15:30 자동 `CANCELLED_EXPIRED` 만료·evidence C의 세션 만료 관찰을 포함한다(`docs/specs/016-investment-education-policy`).
  - 코인 경로는 세션 개념 없는 GTC이며 `docs/specs/020-coin-practice-tutorial`이 delta를 소유한다.
  - 이와 동시에 `intentionId` 없는 일반 리스크관리 OCO(원래 3차 MVP 후보)를 튜토리얼 OCO와 하나의 엔진으로 통합 구현한다 — `docs/specs/021-general-risk-management-oco`가 정본이다. `021`은 `intentionId`를 선택 파라미터로 둬 튜토리얼 OCO를 일반 기능의 특수 사례로 흡수하므로, 3차 착수 시 별도 두 트랙이 아니라 하나의 생성·트리거·취소 엔진만 구현한다. `021`의 코인 우선(세션 없음, GTC) 설계는 유지하되 착수 시점만 3차로 미룬다. 손절·익절 PRICE/PERCENT 입력·계산 정책(`docs/specs/019-exit-price-policy`)은 두 경로 모두에서 변경 없이 재사용한다.
  - 이미 완료된 즐겨찾기·사전 의도·합성 시세·주식 체결 세션 FK·공통 예약 원장(PR #165·#171·#173·#176·#191·#195, `015-limit-order`)은 2차 MVP 완료·legacy 호환으로 계속 유지된다. 현재 `039` 사용자 흐름은 favorite·사전 의도 입력을 완료 전제로 쓰지 않고 BUY 진입가 기준 자동 위험 snapshot을 사용한다.
- **지정가 체결 알림 (NOTI-001~005, 2026-08-07, 2차 → 3차로 재이동, 이슈 #261)** — 지정가 매수·매도 체결 시에만 발생(시장가는 즉시 응답으로 확인되므로 제외), SSE 실시간 push 포함. 범위·전달 방식은 2026-08-04 이슈 #140 확정 내용 그대로이며 착수 시점만 뒤로 밀렸다 — 팀 일정 재조정에 따른 이동이지 요구사항 자체의 변경은 아니다. 선행 조건(지정가 체결 트리거 LMT-002)은 이미 완료돼 있어 기술적 제약으로 미룬 것은 아니다. 상세 요구사항은 §4 "알림" 절 참고
- ~~종목 뉴스 요약~~ → **2차로 이동** (2026-08-03, `docs/specs/012-ai-feedback` FEED-008)
- 8개 투자 지식 과정(투자와 위험, 주식과 코인의 차이, 주문과 체결, 시장가와 지정가, 평가손익과 실현손익, 수수료와 수익률, 분산투자, 투자 계획과 복기)과 객관식 문항, 과정별 최초 완료 배지와 전체 `INVESTMENT_BEGINNER`, 확정 교육 자료 기반 RAG 코치 설명. 2차 MVP의 3단계 실제 API 실습과 분리한다
- AI 주간·월간 리포트 — 투자일기를 1주~1개월 모아 분석한다. ~~2차 AI 피드백은 투자일기에 의존하지 않으므로,~~ **4차에 매도 회고 한 파트가 투자일기를 읽게 됐지만**(2026-08-15 정책 변경, §3 FEED-013 행) **여러 건을 모아 보는 이 리포트와 계획 대비 실제 대조(목표가·손절가)는 그대로 여기 남는다** — 그 대조에 필요한 구조화 필드가 `007-journal`에 없어 자유 텍스트뿐이고, 매도 회고 서술은 일기를 인용할 뿐 목표가 달성 여부를 판정하지 않는다

### 1차 명시적 제외 범위 (2026-07 결정 시점 기록)

> **이 목록은 1차 MVP 당시의 범위 결정 기록이다 — "지금도 없다"는 뜻이 아니다.** 아래 항목 중 실시간 랭킹·AI 피드백·튜토리얼·뉴스 요약·투자일기는 2차 MVP에서 일부 또는 전부 구현됐다(§3 구현 현황 참고). 반면 **관리자 기능, 수익 인증·좋아요·신고·대댓글, 회원 탈퇴·프로필 이미지·파일 업로드, 소셜 계정 연결, 알림, 지정가, 분산락·Kafka·부하테스트는 2026-08-04 현재도 구현되지 않았다.**

- 지정가·예약·부분 체결·슬리피지 (2차 LMT-001~005로 정의됨 — LMT-001~005 구현 완료. 부분 체결·슬리피지는 여전히 범위 밖)
- 실시간 랭킹 (→ 2차 RANK-001 구현 완료)
- AI 피드백·AI 챗봇·그래프 DB (→ 2차 AI 피드백 일부 구현 완료. AI 챗봇·그래프 DB는 여전히 범위 밖)
- 알림 (원래 2차 NOTI-001~005로 정의됐으나 2026-08-07 3차로 재이동, 이슈 #261, 미착수)
- 수익 인증 게시물·좋아요·신고·대댓글
- 관리자 기능
- 튜토리얼과 보상 (→ 2차 3단계 투자 실습 일부 구현 완료. **보상·배지는 2차에서도 제외** — C-004)
- 뉴스 검색·요약 (→ 2차 FEED-001·008 구현 완료)
- 분산락·Kafka 업무 이벤트·부하테스트
- 회원 탈퇴·프로필 이미지·파일 업로드
- 소셜 계정 연결 (기존 회원에 카카오·네이버를 명시적으로 추가 연결하는 기능)
- 만료된 이메일 인증 데이터 정리 배치
- 투자일기 작성·수정·목록·상세, 매도 회고, AI 복기 (→ 2차 JOUR-001·002·003·004·006 구현 완료. JOUR-005 상세는 계약 확정 후 착수 예정, AI 복기는 미착수)

---

## 4. 기능 요구사항과 수용 기준

> 이 절은 1차 MVP 요구사항으로 시작해 2차·3차 MVP 요구사항이 차수 표시와 함께 추가됐다. **각 요구사항 ID의 차수는 그 절의 머리말과 제목의 "(2차 MVP)"·"(3차 MVP)" 표기로 판정한다** — 표기가 없는 것이 1차다. 2차·3차 요구사항 중 실제 구현된 것과 미착수인 것은 §3 구현 현황 표를 본다.
>
> - 1차: AUTH-001~006, ACCT-001~003, MKT-001~008, ORD-001~006, PORT-001~002, COM-001~003
> - 2차: MKT-009, **MKT-010**, PORT-003, LMT-001~005, JOUR-001~006, RANK-001~002, COM-004~006 (3단계 투자 실습 EDU-PRACTICE-*·MKT-PRACTICE-*와 코인 가상 가격 실행 환경 COIN-PRICE-RUNTIME-*, AI 피드백 FEED-*는 이 문서에 요구사항 절을 두지 않고 각각 `docs/specs/016-investment-education-policy`·`docs/specs/026-market-order-practice-tutorial`·`docs/specs/030-coin-practice-price-runtime`·`docs/specs/012-ai-feedback`이 정본이다. **커뮤니티 고도화 COM-004~006도 같은 패턴으로 `docs/specs/022-community-enhancement`가 정본이다** — 종목 기준 분류·대댓글·사진 첨부, 이슈 #246·#247·#248. **관심목록 WATCH-001~003은 `docs/specs/023-watchlist`가 정본이다** — 2차 MVP, 이슈 #252. **커뮤니티 좋아요·인기순 정렬 LIKE-001~002·SORT-001은 `docs/specs/045-community-likes-sort`가 정본이다** — 좋아요 표시·취소, 목록·단건 응답의 `likeCount`·`likedByMe` 노출, `sort=popular` 인기순 정렬, 2차 MVP(COM-001이 1차 제외로 못 박았던 좋아요를 신규 도입 — 2026-08-18 사용자 확정), 대응 이슈 없음·PR #442)
>   - **MKT-010의 제목 표기만 "(1차 고도화)"로 다르다** — 2026-08-06 작업자가 팀 회의 용어를 그대로 쓴 것이며, §3 차수 용어 대응표대로 이 문서의 "2차 MVP"와 같은 차수다. 위 목록이 판정 기준이다.
> - 3차: NOTI-001~005 (2026-08-07, 2차 → 3차로 재이동, 이슈 #261 — 원래 2026-08-04 이슈 #140으로 2차 확정했던 항목이며 §4의 요구사항 내용 자체는 변경 없이 유지, 착수 시점만 재조정)

### 인증과 회원

#### AUTH-001 이메일 회원가입

- 입력: 이메일, 중복되지 않는 닉네임, 8자 이상 비밀번호, 약관 동의, `signupVerificationToken`(AUTH-004)
- 비밀번호는 단방향 해시로 저장한다.
- 이메일 또는 닉네임 중복은 409로 거부한다.
- `signupVerificationToken`이 유효할 때만 가입을 진행한다. 없거나 만료됐거나 이미 사용된 토큰은 409 `EMAIL_VERIFICATION_REQUIRED`로 거부한다.
- 토큰에 묶인 이메일과 요청 이메일이 다르면 같은 코드로 거부한다.
- 성공 시 토큰 소비와 회원·주식계좌·코인계좌 생성을 같은 트랜잭션에서 처리한다.

수용 기준:

- Given 중복되지 않는 입력값과 유효한 `signupVerificationToken`
- When 회원가입 요청
- Then 회원·주식계좌·코인계좌가 한 번만 생성되고 토큰이 소비되며 JWT 토큰이 반환된다.

#### AUTH-002 로그인·토큰

- 일반 로그인은 이미 가입된 이메일 회원의 자격증명을 확인하고 JWT만 발급한다.
- 로그인에서는 회원·계좌·시드머니를 새로 생성하거나 초기화하지 않는다.
- 가입되지 않은 이메일, 잘못된 비밀번호, 비밀번호가 없는 OAuth 전용 회원의 이메일 로그인은 모두 401 `UNAUTHORIZED`로 동일하게 응답한다.
- Access Token과 회전형 Refresh Token을 사용한다.
- Refresh Token 원문은 저장하지 않고 해시만 저장한다.
- 로그아웃 또는 토큰 재발급 시 이전 Refresh Token을 폐기한다.
- 인증이 필요한 API는 `Authorization: Bearer <accessToken>`을 요구한다.

#### AUTH-003 카카오·네이버 OAuth

- `KAKAO`, `NAVER`를 공통 OAuth 어댑터로 구현한다.
- 회원 식별 기준은 `provider + providerUserId`다 — 이메일은 식별자가 아니다.
- 같은 `provider + providerUserId`는 한 회원에게만 연결한다.
- 최초 OAuth 콜백에서 신규 사용자로 판정된 경우에만 회원과 두 계좌를 생성하고 FinPlay 자체 JWT를 발급한다.
- 기존 OAuth 회원의 로그인은 기존 회원·계좌·잔액을 유지하고 JWT만 발급한다.
- OAuth 가입자는 별도의 FinPlay 이메일 인증을 면제한다 (AUTH-004).
- 제공자가 이메일을 제공하지 않으면 400 `OAUTH_EMAIL_REQUIRED`로 거부한다. 카카오·네이버 콘솔에서 이메일 제공 동의를 필수로 설정한다.
- 제공자 이메일이 기존 이메일 회원과 동일해도 **자동으로 연결하지 않는다** — 409 `ACCOUNT_LINK_REQUIRED`로 거부하고 기존 이메일 로그인을 안내한다.
- 명시적인 소셜 계정 연결 기능은 1차 범위 밖이다.
- 내 정보 수정용 OAuth 재인증은 계정 연결이 아니다. 이미 연결된 동일 제공자 계정으로 다시 인증한 경우에만 5분 유효·일회용 `reauthToken`을 발급한다.
- OAuth 재인증은 회원·계좌·시드머니를 생성하지 않으며, 다른 제공자 계정으로 인증하면 403 `REAUTHENTICATION_FAILED`로 거부한다.
- 자동 테스트는 Fake OAuth 제공자를 사용한다.
- 실제 제공자 검증은 키와 Callback URL이 준비된 별도 스모크 테스트다.

#### AUTH-004 이메일 인증 (가입 선행)

- 이메일 회원가입은 이메일 인증을 완료해야만 시작할 수 있다.
- 인증번호는 6자리 숫자이며 유효시간은 5분, 입력 시도는 최대 5회다.
- 5회를 초과하면 해당 인증번호를 즉시 무효화하고 429 `TOO_MANY_REQUESTS`로 응답한다.
- 재발송은 60초 간격이며, 같은 이메일에 1시간 5회·하루 10회로 제한한다. 초과 시 429 `TOO_MANY_REQUESTS`다.
- 재발송하면 이전 인증번호는 즉시 무효화된다 — 유효한 인증번호는 항상 최대 1개다.
- 인증번호가 불일치하거나 만료됐거나 발급된 적 없으면 400 `EMAIL_VERIFICATION_FAILED`로 응답한다.
- 인증 성공 시 30분 유효한 일회용 `signupVerificationToken`을 발급한다.
- 인증번호와 토큰은 원문을 저장하지 않는다 — 인증번호는 전용 시크릿 기반 HMAC-SHA-256, 토큰은 SHA-256 해시로 저장한다. 해시는 저장소 유출에 대한 심층방어이며, 주 방어는 5분 만료와 5회 시도 제한이다.
- 이미 가입된 이메일의 인증 요청은 409 `DUPLICATE_RESOURCE`로 거부한다.
- 인증 단계에서는 `users`와 `accounts` 행을 만들지 않는다 — 두 테이블은 AUTH-001 가입 트랜잭션에서만 생성된다.
- 카카오·네이버 OAuth 가입자는 이 인증을 면제한다 (AUTH-003).
- 자동 테스트와 로컬 실행은 Fake 발송기를 사용한다. 실제 발송 검증은 별도 외부 스모크다.

수용 기준:

- Given 미가입 이메일로 인증번호를 받아 확인에 성공한 상태
- When 발급된 `signupVerificationToken`으로 회원가입 요청
- Then 회원·주식계좌·코인계좌가 한 번만 생성되고, 같은 토큰의 재사용은 409 `EMAIL_VERIFICATION_REQUIRED`로 거부된다.

#### AUTH-005 내 정보 조회·수정

- 조회는 인증 사용자의 `id`, `email`, `nickname`, 가입 방식(`EMAIL`, `KAKAO`, `NAVER`)만 반환한다. 비밀번호 해시와 OAuth 제공자 식별자는 노출하지 않는다.
- 실명 `name` 필드는 추가하지 않으며 화면의 이름은 `nickname`을 사용한다.
- 이메일 회원의 닉네임·이메일 변경은 현재 비밀번호 확인을 요구한다.
- OAuth 전용 회원의 닉네임·이메일 변경은 AUTH-003에서 발급한 5분 유효·일회용 `reauthToken`을 요구한다.
- **(2026-08-16 결정)** 이메일 변경은 이 API 계약대로 계속 동작하지만, **프론트엔드 내 정보 화면은 OAuth 전용 회원에게 이메일 변경 UI를 노출하지 않는다.** 재인증 팝업 왕복(카카오·네이버 재로그인 → `reauthToken` 회수)이 아직 프론트에 구현되지 않은 상태에서 "곧 지원 예정"으로 오인시키는 안내 문구만 있던 것을 정정한 결정이다 — OAuth 회원의 이메일 변경은 제품 화면에서 제외된 기능으로 다룬다. 닉네임 변경은 이 결정의 대상이 아니다(별도로 재인증 팝업 플로우가 구현되면 프론트에도 열린다).
- 닉네임 변경은 중복되지 않는 새 `nickname`과 재인증 증명을 받아 즉시 반영한다. 이메일·계좌·잔액·주문·체결은 변경하지 않는다.
- 비밀번호 변경은 현재 비밀번호 확인을 요구하고, 새 비밀번호는 가입과 같은 규칙(8자 이상 100자 이하)을 따르며 현재 비밀번호와 달라야 한다.
- OAuth 전용 회원의 비밀번호 변경은 거부한다. 바꿀 비밀번호 자체가 없는 계정이므로 `reauthToken`으로도 허용하지 않는다.
- 비밀번호 변경이 완료되면 기존 Refresh Token을 모두 폐기하되 요청한 기기가 쓸 새 Access·Refresh 토큰 쌍을 함께 발급한다. 따라서 다른 기기만 로그아웃되고 요청한 기기는 재로그인 없이 이어서 사용한다(재로그인을 요구하는 아래 이메일 변경과 다른 점이다). 기존 Access Token은 짧은 만료시간까지 유효할 수 있다.
- 이메일 변경 요청은 새 이메일과 재인증 증명을 검증한 뒤 새 이메일로 6자리 인증번호를 발송한다. 확인 전까지 기존 이메일을 유지한다.
- 이메일 변경 인증번호는 5분 유효, 최대 5회 시도, 60초 재발송 간격, 1시간 5회·하루 10회 제한을 적용한다. 재발송하면 이전 번호는 즉시 무효화한다.
- 이메일 변경 확인은 인증 사용자·새 이메일·인증번호를 하나의 변경 요청에 묶어 검증하고, 성공 시에만 `users.email`을 원자적으로 변경한다.
- 이미 다른 회원이 사용하는 이메일·닉네임은 409 `DUPLICATE_RESOURCE`로 거부한다.
- 이메일 변경이 완료되면 기존 Refresh Token을 모두 폐기하고 재로그인을 요구한다. 기존 Access Token은 짧은 만료시간까지 유효할 수 있다.
- OAuth 회원의 이메일을 변경해도 `provider + providerUserId` 연결은 유지한다. 이후 OAuth 로그인에서 제공자 이메일로 회원 이메일을 자동 덮어쓰거나 다른 계정과 병합하지 않는다.
- 재인증 토큰과 이메일 변경 인증번호 원문은 저장하지 않고 해시만 저장하며, 성공 시 한 번만 소비한다.

수용 기준:

- Given 이메일 회원이 올바른 현재 비밀번호를 입력하거나 OAuth 회원이 동일 제공자로 재인증한 상태
- When 닉네임을 변경하거나 비밀번호를 변경하거나 새 이메일 인증을 완료
- Then 본인 정보만 변경되고 기존 계좌·시드머니·잔액·주문·체결은 그대로 유지된다.
- And 잘못된 현재 비밀번호·다른 OAuth 계정·만료 또는 재사용된 재인증 증명은 정보 변경 없이 거부된다.
- And 비밀번호 변경이 성공하면 요청한 기기는 새로 받은 토큰 쌍으로 로그인 상태를 유지하고 다른 기기만 로그아웃된다.
- And OAuth 전용 회원의 비밀번호 변경 요청은 비밀번호 대조 없이 거부된다.

#### AUTH-006 비밀번호 재설정 (비로그인)

- 비밀번호를 잊은 이메일 회원은 가입 이메일로 6자리 인증번호를 받아 비밀번호를 재설정한다. 요청 경로는 인증이 필요 없는 공개 경로다.
- 인증번호는 6자리 숫자이며 유효시간은 5분, 입력 시도는 최대 5회다. 5회를 초과하면 해당 인증번호를 즉시 무효화하고 429 `TOO_MANY_REQUESTS`로 응답한다.
- 재발송은 60초 간격이며, 같은 이메일에 1시간 5회·하루 10회로 제한한다. 초과 시 429 `TOO_MANY_REQUESTS`다.
- 발송 제한은 **이메일 주소 단위**로 집계하며, 아래 404·409로 거부된 요청도 같은 집계에 포함한다. 거부 응답이 계정 상태를 드러내므로 거부 요청을 세지 않으면 같은 주소를 무제한으로 두드려 상태를 확인할 수 있기 때문이다. 이미 제한을 넘겨 429가 된 요청 자체는 집계에 남기지 않는다 — 남기면 하루 10회 제한이 사실상 영구 차단이 된다.
- 발송 제한 판정은 회원 존재 확인보다 **먼저** 한다. 순서가 반대면 제한을 초과한 요청자도 계정 상태를 계속 알아낼 수 있다. AUTH-004의 가입 인증 발송(중복 확인 → 발송 제한)과 순서가 의도적으로 반대다.
- 발송 단계에서 가입되지 않은 이메일은 404 `NOT_FOUND`, 비밀번호가 없는 소셜 로그인 전용 회원은 409 `SOCIAL_ACCOUNT_ONLY`로 거부하고 인증번호를 발송하지 않는다. 확인 단계는 아래와 같이 404를 쓰지 않는다.
- 이 응답 구분은 계정 존재 여부와 가입 방식이 드러나는 것을 감수한 결정이다. 근거는 AUTH-004의 가입 인증 요청이 이미 가입 여부를 409 `DUPLICATE_RESOURCE`로 드러내고 있다는 점과, 같은 주소의 반복 조회를 위 발송 제한이 막는다는 점이다. 다만 서로 다른 주소를 한 번씩 훑는 광범위 스캔은 이메일 단위 제한으로 막히지 않으며, IP·디바이스 단위 제한은 1차 범위 밖이다.
- 재발송하면 이전 인증번호는 즉시 무효화된다 — 유효한 인증번호는 항상 최대 1개다.
- 인증번호 원문은 저장하지 않고 **전용 시크릿**(`PASSWORD_RESET_SECRET`) 기반 HMAC-SHA-256 해시로만 저장한다. AUTH-004의 가입 인증번호와 시크릿을 공유하지 않는다 — 재설정 인증번호는 탈취 시 기존 계정 탈취로 직결되어 영향 범위가 다르다.
- 인증번호 확인은 이메일·인증번호·**새 비밀번호를 한 요청으로 함께** 받아 즉시 적용한다. 중간 단계 토큰(`passwordResetToken` 같은 것)을 발급하는 2단계 흐름은 쓰지 않는다.
- 확인에 성공하면 `users.password_hash`를 원자적으로 교체하고 기존 Refresh Token을 모두 폐기한다. **새 토큰 쌍은 발급하지 않는다** — 비로그인 흐름이라 발급할 대상 세션이 없다. 따라서 재설정은 **항상 전 기기 로그아웃**이며 사용자는 새 비밀번호로 다시 로그인해야 한다. 요청한 기기의 세션을 유지하는 AUTH-005의 비밀번호 변경과 의도적으로 다르다.
- 확인 단계에서 미가입 이메일·인증번호 불일치·만료·재발송으로 무효화된 이전 인증번호·이미 소비된 인증번호·요청 이력 없음은 **모두 같은 400** `EMAIL_VERIFICATION_FAILED`로 응답하고 사유를 구분하지 않는다.
- 미가입 이메일을 404로 구분하지 않는 것은 발송 단계와 **의도적으로 다르다**. 확인 경로에는 이메일 단위 발송 제한이 없어서, 404로 구분하면 아무 이메일·아무 인증번호로 호출해 응답 코드만 보고 가입 여부를 알아내는 **횟수 제한 없는 계정 열거 오라클**이 된다. 발송 단계가 계정 열거를 감수한 근거("같은 주소의 반복 조회는 하루 10회 제한이 막는다")가 이 경로에서는 성립하지 않는다.
- 같은 이유로 확인 단계의 계정 상태 판정(미가입·소셜 로그인 전용)은 인증번호 검증을 **통과한 뒤에만** 한다.
- 인증번호 검증에 실패해도 시도 횟수 증가는 유지(커밋)한다. 실패와 함께 되돌리면 시도 5회 제한이 무력화되어 무차별 대입을 막지 못한다.
- 인증번호 소비·`password_hash` 교체·Refresh Token 폐기는 모든 판정을 통과한 뒤에만 실행해 부분 성공 상태를 만들지 않는다. 판정에 걸려 거부되면 인증번호도 소비되지 않는다.
- 이미 발급된 Access Token은 블랙리스트되지 않아 남은 만료 시간 동안 다른 기기에서도 유효할 수 있다. Refresh Token 폐기는 갱신만 막는다 — 이는 감수한 한계다.
- 발송 단계에서는 `users`·`accounts`·Refresh Token을 만들거나 바꾸지 않는다.
- 자동 테스트와 로컬 실행은 Fake 발송기를 사용한다. 실제 발송 검증은 별도 외부 스모크다.

구현 단계: 발송(Issue #115)과 확인·적용(Issue #116)이 모두 구현돼 위 항목 전체가 충족된다. 엔드포인트는 `POST /api/auth/password-resets`(발송, 202)와 `POST /api/auth/password-resets/confirm`(확인·적용, 204) 두 개이며 둘 다 인증이 필요 없는 공개 경로다.

수용 기준:

- Given 비밀번호로 가입한(`users.password_hash`가 있는) 회원의 이메일
- When 비밀번호 재설정 인증번호 발송을 요청
- Then 202로 수락되고 가입 이메일로 6자리 인증번호가 발송되며, 저장소에는 HMAC 해시만 남고 원문은 DB에도 로그에도 남지 않는다.
- And 가입되지 않은 이메일은 404, 비밀번호가 없는 소셜 전용 회원은 409로 거부되어 메일이 발송되지 않으며, 거부된 두 요청도 발송 제한 집계에 포함된다.
- And 재발송에 성공하면 같은 이메일의 이전 인증번호는 즉시 무효화된다.
- And 발송 성공·실패와 무관하게 `users`·`accounts`·`refresh_tokens`는 변하지 않는다.

- Given 유효한 인증번호를 발송받은 회원
- When 이메일·인증번호·새 비밀번호를 한 요청으로 보내 확인
- Then 204로 처리되고 새 비밀번호로 로그인할 수 있으며 기존 비밀번호로는 로그인할 수 없다.
- And 응답에 새 토큰이 없고 기존 Refresh Token은 모두 폐기되어 모든 기기가 재로그인해야 한다.
- And 같은 인증번호를 다시 사용하면 400으로 거부된다.
- And 미가입 이메일·인증번호 불일치·만료·재사용은 모두 같은 400으로 응답해 사유가 구분되지 않으며, 같은 인증번호 5회 초과 시도는 429이고 그 인증번호는 즉시 무효화된다.
- And 확인이 거부되면 `users.password_hash`와 Refresh Token은 그대로이고 시도 횟수 증가만 남는다.
- And 확인 성공·실패와 무관하게 이메일·닉네임·`social_accounts`·계좌·시드머니·잔액·주문·체결은 변하지 않는다.

### 계좌

#### ACCT-001 시장별 계좌

- 회원당 `STOCK` 계좌 1개와 `CRYPTO` 계좌 1개만 존재한다.
- 각 계좌의 최초 현금은 10,000,000원이다.
- 계좌 간 이체는 지원하지 않는다.
- `UNIQUE(user_id, market)`로 중복 생성을 막는다.

#### ACCT-002 계좌 조회

- 현금잔고, 보유평가액, 총평가액, 실현손익, 미실현손익을 시장별로 반환한다.
- 평가값은 저장하지 않고 최신 가격으로 계산한다.
- 수익률(`returnRate`, `(총평가액 − 시드머니) ÷ 시드머니`)은 응답에 포함하지 않는다 — 종목별 수익률(투자원가 기준)과 분모가 달라 나란히 보여주면 혼동을 준다는 판단으로, 포트폴리오·내정보 화면에서 표시를 걷어내며 응답에서도 제거했다(2026-08-17). 이슈 #390에서 도입한 scale 8 계산식은 이제 어디에도 없다.
- ACCT-003(전체 포트폴리오 합산, Issue #51)은 이 계산식을 그대로 재사용하며 별도 계산식을 만들지 않는다(구현: `docs/specs/006-portfolio-query/plan.md` 이슈 #81 절).

#### ACCT-003 전체 포트폴리오 합산 요약

- 인증 사용자의 `STOCK`·`CRYPTO` 계좌를 합산한 총평가자산·평가손익·실현손익을 반환한다.
- 시장별 계좌 요약과 동일한 가격·손익 계산 계약을 사용하며 합산 결과를 별도 원장으로 저장하지 않는다.
- 한 시장만 보유하거나 두 시장 모두 비어 있어도 누락 없이 0을 포함한 합산 결과를 반환한다.
- 다른 사용자의 계좌·체결 데이터는 합산하지 않는다.

### 종목과 시세

#### MKT-001 종목 범위

- 현재 프론트의 주식 16종과 코인 12종을 사용한다.
- 종목 심볼은 유일해야 하며 시장·호가단위·최소주문금액·거래가능 여부를 가진다.

#### MKT-002 주식 실제 과거 데이터 재생

- KIS Open API로 조회한 과거 데이터에서 허용된 주식 16종만 추출해 형식·누락을 검증한 뒤 MySQL에 정규화된 1분봉으로 저장한다.
- 저장된 1분봉을 실제 서비스 시간 09:00~15:30 KST에 1배속(1분마다 1개 공개)으로 재생한다.
- 모든 회원은 같은 과거 거래일의 같은 분봉을 동시에 본다 — 회원별로 달라지는 것은 현금·보유수량·거래내역·손익뿐이다.
- 재생할 원본 거래일은 장 시작 전에 고정되며 당일 장중에는 바뀌지 않는다. 서버가 재시작해도 같은 원본 거래일을 계속 재생한다. 회원 화면에 실제 원본 거래일을 표시한다.
- 09:00~09:00:59(첫 분봉)에는 첫 분봉의 시가를 현재가로 노출하고 이 구간의 주문도 시가로 체결한다. 09:01부터는 마감이 완료된 마지막 분봉의 종가를 현재가·체결가로 쓴다. 아직 마감하지 않은 분봉의 고가·저가·종가는 노출하지 않는다.
- 준비된(검증 완료) 원본 거래일 데이터가 없으면 그날은 주식 시장을 열지 않는다.
- 이 재생 방식은 공개 배포의 기본 공급자이며, KIS 실시간 전환 여부와 무관하게 **삭제하지 않고 유지한다** (C-007).
- 주말·공휴일과 재생 시간 밖에는 주식 시장이 닫힌 상태다.
- 테스트는 주입 가능한 `Clock`으로 개장·장중·마감을 재현한다.

#### MKT-003 코인 실시간 시세

- 빗썸 WebSocket에서 12종 최신 틱을 수신한다.
- 최신 가격과 수신시각, 연결상태를 Redis에 저장한다.
- 동일 심볼의 과거 틱이 최신 틱을 덮어쓰지 못한다.

#### MKT-004 시세 장애

- 연결이 끊겼거나 해당 코인의 시세를 한 번도 받은 적이 없으면 그 코인의 화면 조회와 주문을 모두 거부한다 — 보여줄 가격 자체가 없는 상태다.
- 재연결 후 새 틱을 받은 경우에만 주문을 자동 재개한다.
- 연결이 살아있고 마지막 가격을 받은 적이 있으면, 그 가격의 관측 시각이 얼마나 오래됐든(경과 시간과 무관하게) 화면 조회와 주문 체결에 모두 그 마지막 가격을 사용하며 항상 정상 상태(`status: "AVAILABLE"`)로 취급한다(`036-remove-crypto-stale-status`).
- 관측 시각은 웹소켓 체결 수신과 REST 폴링 성공 중 나중 것을 쓴다 — 체결이 뜸해도 폴링이 살아있으면 신선한 상태를 유지한다.
- 임의 가격·보간값·호가 기준가로 몰래 전환하지 않는다. 표시·체결에 쓰는 값은 언제나 실제로 수신된 마지막 가격이다.

#### MKT-005 데이터 수집과 보관

- 과거 1분봉은 한국투자증권(KIS) Open API **REST 호출(주식일별분봉조회)**로 **직전 영업일** 09:00~15:30 구간을 수집한다. 한 번의 호출로 받을 수 있는 분봉 수에 상한이 있어 종목당 여러 번 연속 호출해 이어붙인다. 데이터 재생·주문 로직은 수집 방식이 바뀌어도 수정하지 않는다. 엔드포인트·파라미터 등 구현 세부는 `docs/specs/003-market-data/plan.md`를 따른다.
- 배치 실행 시각은 **평일 08:10 KST 수집**, **평일 08:40 KST 재생세션 확정**, **09:00 재생 시작**이다. 당일 분봉이 익영업일 오전 8시경 제공되는 것을 확인해 여유를 둔 값이다. 08:40 시점에 직전 영업일 데이터가 아직 없으면 "검증 완료된 최신 거래일"을 고르는 규칙에 따라 그 전 영업일로 자연 폴백한다.
- 재생 대상 거래일은 준비상태만 가진 작은 모델(재생세션)로 관리한다 — 서비스 날짜, 원본 거래일, 준비상태(`PREPARING`·`READY`·`FAILED`), 결과가 결정된 시각(`resolved_at`), 실패사유를 기록한다. `READY`·`FAILED`는 결과가 결정된 상태이므로 `resolved_at`이 필수이고, `PREPARING`은 아직 결정 전이라 `resolved_at`·실패사유 모두 없다. 장 시작 전 검증이 완료된 가장 최신 거래일을 선택해 `READY`로 전환하고, 당일 장중에는 이 선택을 바꾸지 않는다.
- 한 번 받아들인(성공·부분성공) 거래일 데이터는 불변으로 취급한다. 같은 거래일에 동일한 수집 결과를 다시 수집하면 기존 분봉·재생세션을 바꾸지 않고 감사 이력만 남긴다(`SKIPPED_DUPLICATE`). 같은 거래일에 상충하는 수집 결과가 들어오면 기존 데이터·세션을 바꾸지 않고 수집 자체를 거부하며 실패로 기록한다 — 서로 다른 수집 결과의 분봉이 섞이지 않는다. 그 거래일에 실패 이력만 있고 받아들인 데이터가 없다면 재시도를 허용한다. 거래일 데이터의 자동 교체·원자적 재삽입·버전 관리는 MVP에서 제외한다. **동일 거래일 재수집 판정 로직의 세부 구현은 MVP 데모 범위에서 제외하고 후속 이슈(장기운영 방어 로직)에서 진행하며, `UNIQUE(instrument_id, trading_date, candle_time)` 제약으로 기본 재실행 멱등성만 우선 확보한다.**
- 시장의 `OPEN`·`CLOSED`는 재생세션에 저장하는 상태가 아니다 — 준비상태가 `READY`인 세션과 Clock(09:00~15:30 KST·영업일 여부)을 조합해 그때그때 계산한다. 준비상태가 `READY`가 아니면 해당 거래일은 주식 시장을 이용할 수 없다.
- 수집 실패는 범위에 따라 다르게 처리한다.
  - API 응답 파싱 실패, 거래일 불일치, 필수 필드 부재 등 전체 데이터 훼손 → 해당 서비스 날짜의 재생세션을 `FAILED`로 두고 그날 주식 시장을 열지 않는다. 이전 가격이나 임의값으로 대체하지 않는다.
  - 특정 종목의 데이터만 손상되거나 필수 데이터가 부족 → 해당 종목은 그 거래일의 유효한 분봉이 없어 가격 조회·주문에서 거부되고, 나머지 종목의 개장에는 영향을 주지 않는다. 이를 위한 날짜별 종목 전용 상태 테이블이나 컬럼은 별도로 두지 않는다.
  - 실제 거래정지·거래 없음으로 분봉 수가 적은 정상적인 경우까지 단순 개수만으로 오류 처리하지 않는다. 종목별 정상 범위의 구체적인 기준(임계치)과 그 기준을 저장할 방식은 KIS 상품의 데이터 형식과 timestamp 의미(거래 없는 분을 생략하는지 여부)가 확인된 뒤 결정한다 — 확인 전까지는 임시 숫자를 포함해 임의로 확정하지 않는다(Decision Gate).
- 수집 시도 자체의 이력(성공·부분성공·실패, 실패사유, 수집시각, 중복식별값)은 저장에 성공하지 못한 경우도 포함해 별도로 기록한다.
- `UNIQUE(instrument_id, trading_date, candle_time)`으로 동일 분봉의 중복 저장을 막는다. 수집 작업을 재실행해도 중복되지 않는다(멱등).
- 최근 20영업일의 정규화된 1분봉만 MySQL에 보관한다. 현재 재생 중인 거래일은 삭제하지 않는다. 20영업일보다 오래된 분봉은 정리 작업으로 삭제한다. **정리 작업(Cleanup Job) 구현은 MVP 데모 범위에서 제외하고 후속 이슈(장기운영 방어 로직)에서 진행한다 — 데모 기간에는 20영업일치가 쌓이지 않는다.**
- 주문·체결·보유·손익 기록은 분봉 삭제와 무관하게 계속 보존한다.
- 데이터 출처, 실제 거래일, 수집시각을 기록한다. 분봉 단위 검증상태는 별도로 저장하지 않는다 — 검증을 통과한 분봉만 저장되므로 수집 시도 이력(성공·부분성공·실패)만으로 충분하다.

#### MKT-006 공개 서비스 데이터 정책

- 비상업적 교육용 공개 서비스 정책(C-006)을 따른다.
- 회원에게 원본 데이터 다운로드 기능이나 원본을 조회하는 외부 API를 제공하지 않는다.
- KIS 원본 데이터 파일은 공개 저장소에 커밋하지 않는다.

#### MKT-007 주식 시세 공급자 전환 구조 (C-007)

> **1차 MVP 범위**: 이 요구사항에서 1차 MVP가 실제로 만드는 것은 **공통 계약 `StockPriceProvider`와 그 유일한 구현체 `KisHistoricalReplayPriceProvider`**까지다. 실시간 Provider도, **설정 기반 공급자 전환 구조(`STOCK_FEED_PROVIDER`·`SERVICE_EXPOSURE`·`KIS_PUBLIC_DISPLAY_APPROVED`)와 그 fail-fast 방어도 1차 MVP에서는 구현하지 않는다** — 고를 대상이 하나뿐인데 선택 스위치만 두면, 뒤에 아무것도 연결되지 않은 채 자기 값 하나를 거부하기만 하는 코드가 남기 때문이다. 아래 설정·조합 요구는 실시간 Provider가 도입되는 KIS 실시간 후속에서 **구현체와 함께** 되살린다. 요구 자체는 장기 정본으로 유지한다.

- 주식 시세 공급자를 공통 계약 `StockPriceProvider`로 분리한다. **1차 MVP의 구현체는 `KisHistoricalReplayPriceProvider` 하나뿐이며, 고를 대상이 하나이므로 설정으로 선택하지 않고 직접 등록한다.**
  - `KisHistoricalReplayPriceProvider` — KIS Open API로 조회한 과거 실제 1분봉 재생. **공개 배포의 기본 Provider이자 1차 MVP의 유일한 주식 시세 경로**다.
  - `KisRealtimePriceProvider` — KIS WebSocket 실시간 체결가 수신. 개발자 본인만 접근하는 개인 개발·본인 전용 검증 환경에서만 사용한다. **(1차 MVP 범위 아님 — KIS 실시간 후속)**
- Provider는 내부 가격 모델만 반환한다. 아래 소비 계층은 어느 Provider가 동작 중인지 몰라야 한다 — `PriceQueryService`, 주식 가격·캔들 API, SSE, 모의 주문 체결, 보유자산 평가손익. **이 이음매는 구현체가 하나뿐인 1차 MVP에도 유지한다** — 두 번째 구현체가 들어올 때 소비 계층을 고치지 않기 위한 것이며, 소비 계층이 실제로 이 계약에 의존하므로 빈 껍데기가 아니다.
- 화면에 보이는 가격과 모의 주문 체결가격은 **항상 같은 Provider**에서 나온다. 두 경로가 서로 다른 공급자를 쓰지 않는다.
- KIS WebSocket은 체결 틱 단위로 들어오므로, 차트(1분봉)에 쓰려면 **서버가 틱을 1분 OHLCV로 집계하는 책임**을 갖는다. 집계 결과는 `KisHistoricalReplayPriceProvider`가 제공하는 분봉과 같은 모델이어야 한다. **(1차 MVP 범위 아님 — KIS 실시간 후속)**
- **(1차 MVP 범위 아님 — KIS 실시간 후속)** 실행 환경은 다음 설정으로 결정한다.

| 설정 | 값 | 의미 |
|---|---|---|
| `STOCK_FEED_PROVIDER` | `KIS_REALTIME` · `KIS_HISTORICAL` | 어느 Provider를 쓸지 |
| `SERVICE_EXPOSURE` | `PRIVATE` · `PUBLIC` | `PRIVATE`는 개발자 본인만 접근하는 로컬·접근 통제 환경, `PUBLIC`은 본인 외 누구든 접근하는 환경(팀원·튜터·심사위원 시연 포함) |
| `KIS_PUBLIC_DISPLAY_APPROVED` | 기본 `false` | 공개 환경의 KIS 실시간 표출에 대한 서면 허가·계약 확인 결과 반영 (과거 데이터는 공공데이터로 확인되어 이 플래그와 무관하게 공개 가능) |

- **(1차 MVP 범위 아님 — KIS 실시간 후속)** 허용 조합은 넷뿐이다.

| SERVICE_EXPOSURE | STOCK_FEED_PROVIDER | 조건 | 용도 |
|---|---|---|---|
| PRIVATE | KIS_REALTIME | 개발자 본인만 접근 | 개인 개발·본인 전용 검증 |
| PRIVATE | KIS_HISTORICAL | 없음 | 로컬 재생 테스트 |
| PUBLIC | KIS_HISTORICAL | 없음 | **현재 공개 배포 기본값. 팀원·튜터·심사위원 시연도 이 조합이다** |
| PUBLIC | KIS_REALTIME | `KIS_PUBLIC_DISPLAY_APPROVED=true` **이고** 한국투자 서면 허가·계약 근거가 있을 때만 | 허가 이후 전환 |

- **(1차 MVP 범위 아님 — KIS 실시간 후속) fail-fast** — `SERVICE_EXPOSURE=PUBLIC` + `STOCK_FEED_PROVIDER=KIS_REALTIME` + `KIS_PUBLIC_DISPLAY_APPROVED=false` 조합은 애플리케이션 **시작 단계에서 실패**시킨다. 경고 로그만 남기고 기동하지 않는다.
- **이 방어는 실시간 Provider와 한 몸이다.** KIS 실시간 후속에서 실시간 구현체를 도입할 때 위 설정 3종·허용 조합·fail-fast를 **반드시 함께** 되살린다. 구현체만 먼저 들어오고 방어가 빠지면 공개 환경에서 무허가 실시간 표출이 가능해진다 (C-007 위반).
- KIS 앱키·시크릿(`KIS_APP_KEY`·`KIS_APP_SECRET`)은 1차 MVP에서도 **과거 분봉 수집 배치**가 사용한다. 다만 키가 없어도 애플리케이션 기동과 자동 테스트는 정상 실행되어야 한다 — 키가 없으면 수집 배치가 실패해 그날 재생세션이 `FAILED`가 될 뿐이다.
- 1차 MVP의 주식 시세 자동 테스트는 **Fake KIS 과거분봉 응답 클라이언트**를 사용한다. 실제 KIS Open API 호출 검증은 별도 외부 스모크로 구분해 보고한다 (C-005).
- **(1차 MVP 범위 아님 — KIS 실시간 후속)** 실시간 Provider의 자동 테스트는 `FakeKisRealtimePriceProvider`를 사용하고, 실제 KIS WebSocket 연결 검증은 별도 외부 스모크로 구분해 보고한다.

#### MKT-008 코인 차트(1분봉) 조회

- 코인 종목도 주식과 같은 캔들 API(`GET /api/instruments/{instrumentId}/candles`)로 1분봉 차트 데이터를 조회할 수 있다. 회원은 주식·코인 어느 화면에서도 같은 응답 형식의 차트를 본다.
- 코인 1분봉의 데이터 출처는 **빗썸 공개 캔들 REST API**다. 인증·API Key가 필요 없는 공개 시세 데이터이며, 요청 시점에 조회해 그대로 정규화해 반환한다.
- **코인 차트는 실시간 차트다 — 과거 데이터 재생이 아니다.** 주식의 `KIS_HISTORICAL`(MKT-002)은 옛 거래일을 오늘 다시 트는 방식이지만, 빗썸 REST 캔들은 지금 이 순간까지의 실제 시장을 돌려준다. WebSocket 틱(MKT-003)과 REST 분봉은 같은 지금의 시장을 다른 해상도로 본 것이다 — 틱은 개별 체결, 분봉은 그 체결들의 1분 묶음이다.
- REST를 쓰는 이유는 **WebSocket이 "지금 가격"만 주고 직전 봉들을 주지 않기 때문**이다. 틱만으로 차트를 그리면 서버 기동 직후 화면에 점 하나뿐이고 200분을 기다려야 봉이 채워진다.
- **(2026-08-06 MKT-010으로 대체 — 구현 완료)** 코인 1분봉을 Redis에 저장하지 않는다는 기존 결정은 MKT-010에서 뒤집혔고 구현됐다. 다만 **새 MySQL 테이블을 만들지 않는다는 원문 의도는 그대로 유지**하며, 코인 시세의 정본도 여전히 외부(빗썸)다 — Redis 캐시는 정본을 대체하는 게 아니라 최근 짧은 구간만 보완한다. 세부는 MKT-010을 따른다.
- **진행 중인(아직 마감하지 않은) 분봉을 포함해 반환한다** — 그것이 지금의 실시간 시세다. **주식(MKT-002)은 미마감 봉을 제외하는데 코인은 포함하며, 이 차이는 의도된 것이다** — 주식의 제외 규칙은 과거 거래일 재생에서 미공개 분봉이 새지 않게 하고 체결가 계약과 어긋나지 않게 하려는 것이고, 코인은 재생이 아니라 실시간이라 가릴 대상이 없다.
- 분 이하 해상도의 실시간 갱신은 프론트가 이 캔들 API를 짧은 주기로 재조회해 얻는다 — 진행 중 분봉이 매 호출마다 최신 값으로 갱신되므로 그것으로 충분하다(코인 전용 SSE 스트림은 두지 않는다. **이 SSE 제외 결정은 MKT-010에서도 유지된다** — 집계 결과는 여전히 이 캔들 API의 재조회로 전달한다). **(2026-08-06 MKT-010으로 대체 — 구현 완료)** 서버가 틱을 분봉으로 집계하지 않는다는 기존 결정은 MKT-010에서 뒤집혀 구현됐다 — 코인에도 주식의 틱 집계기(MKT-007)에 대응하는 구성요소(`CryptoCandleStore`)가 생겼다. 이 캔들 API는 먼저 그 결과(Redis)를 보고 없는 구간만 빗썸 REST로 보충한다. 세부는 MKT-010을 따른다.
- 한 번의 요청으로 반환하는 분봉 개수에는 상한이 있다(빗썸 API 상한과 동일). 요청 범위가 상한을 넘으면 `to` 기준 최신 분봉부터 상한 개수만 반환한다 — 이 상한은 계약으로 명시하며 조용히 잘라내지 않는다.
- 빗썸 조회가 실패하면(타임아웃·비정상 응답) 실패를 그대로 오류로 반환한다. 마지막으로 받은 캔들이나 임의값으로 대체하지 않고, 빈 배열로 성공을 위장하지도 않는다 (MKT-004와 같은 원칙).
- 코인 차트의 캔들과 실시간 현재가(MKT-003의 WebSocket 틱)는 출처가 다르다 — 캔들은 마감된 분봉, 현재가는 최신 틱이다. 이 둘을 같은 값으로 맞추려고 서버에서 보정하지 않는다.
- 원본 응답을 그대로 내려주거나 다운로드하게 하지 않는다. 정규화된 분봉 필드만 노출한다 (MKT-006과 같은 원칙).
- 자동 테스트는 Fake 구현을 사용한다. 실제 빗썸 REST 연동 검증은 별도 외부 스모크로 구분해 보고한다 (C-005).

#### MKT-009 캔들 조회 기간 확장 — 일봉/주봉/월봉 (2차 MVP)

- 캔들 API(`GET /api/instruments/{instrumentId}/candles`)의 `interval` 허용값을 `1m`에서 `1m | 1d | 1w | 1M`(1분봉/일봉/주봉/월봉)로 확장한다.
- **주식**: 정본인 1분봉(`stock_candles`, MKT-002)을 거래일·주·월 단위로 집계해 제공한다. 신규 저장 테이블을 두지 않는다.
- **코인**: 빗썸 공개 캔들 REST의 일/주/월봉 엔드포인트(`/v1/candles/days`·`/weeks`·`/months`)에 위임한다 — 기존 1분봉(MKT-008)과 동일하게 저장 없이 요청 시점에 조회해 중계한다.
- 응답은 기존 `CandleResponse`(시가·고가·저가·종가·거래량) 필드 구조를 그대로 유지한다. 집계 경계(주·월의 시작 기준), 미마감 봉 처리, 오류 코드 등 세부 계약은 착수 시 spec에서 확정한다.
- 범위: 2차 MVP(1차 고도화). 1차 MVP API 계약(`interval=1m`)에는 포함하지 않았다.
- **구현 완료 (2026-08-04, `docs/specs/013-candle-interval`, PR #151)** — `interval`은 현재 `1m·1d·1w·1M` 4종을 받는다. 미지원 값은 400 `VALIDATION_ERROR`다. 주식은 `stock_candles` 1분봉을 일·주(월요일 시작)·월(1일 시작) 버킷으로 집계하고, 코인은 빗썸 `days`·`weeks`·`months` 엔드포인트에 위임한다. 아래 §5의 "1차 API 계약은 `interval=1m`만 포함한다"는 서술은 1차 시점 기준이며 현재 계약이 아니다 — 실제 계약은 `docs/api-contracts.md`가 정본이다.

#### MKT-010 코인 틱 집계와 캐싱 (1차 고도화)

> 이 요구사항은 MKT-008의 기존 결정 두 가지를 뒤집는다(supersede) — "서버는 틱을 분봉으로 집계하지 않는다", "코인 1분봉을 Redis에 저장하지 않는다". 2026-08-06 튜터 피드백("웹소켓 틱 받아서 분봉으로 캐싱 집계 쪽으로 풀어가면 재밌어 보인다")을 계기로 재검토해 뒤집기로 했다. MKT-008 해당 문장에는 이 대체 사실을 각주로 남겨둔다.

**왜 뒤집었나**

- 코인 캔들 조회(MKT-008)는 요청이 올 때마다 빗썸 REST를 호출하며 **캐시가 전혀 없다**(`BithumbRestCandleProvider` — 클래스 주석에 "저장·캐시 없음"으로 명시). 게다가 MKT-008은 실시간 갱신을 "프론트가 이 API를 짧은 주기로 재조회"로 설계했다. 즉 **화면에 코인 차트를 띄운 사용자 수 × 재조회 주기**만큼 빗썸 호출이 나간다.
- 사용자가 늘면 외부 호출도 비례해 늘고, 빗썸이 느려지거나 막으면 우리 차트도 같이 죽는다. 우리가 통제할 수 없는 외부 서비스가 사용자 경험의 직접적인 병목이다.
- 반면 빗썸 WebSocket(MKT-003)은 **사용자 수와 무관하게 연결 한 줄**로 실시간 시세를 이미 받고 있다. 이 유입을 차트에 쓰면 외부 호출량을 사용자 수에서 떼어낼 수 있다.
- 주식 쪽에는 이미 같은 성격의 틱 집계 책임이 요구사항으로 정의돼 있다(MKT-007, KIS 실시간 후속) — 코인만 예외로 둘 이유가 약해졌다.

**무엇을 하나**

- 서버가 빗썸 WebSocket 유입을 모아 **진행 중인 1분봉을 직접 만들고 Redis에 보관**한다. `interval=1m` 캔들 조회(MKT-008)는 먼저 이 Redis 데이터를 보고, 없는 구간만 빗썸 REST(MKT-008 기존 경로)로 보충한다.
- **새 MySQL 테이블은 만들지 않는다** — MKT-008의 "코인 캔들 전용 테이블을 만들지 않는다" 원칙은 유지한다. 캐싱은 Redis에만 한다.
- 빗썸 REST 조회가 실패해도 Redis에 있는 최근 구간은 계속 보여줄 수 있어야 한다(fallback). 반대로 Redis가 죽으면 기존처럼 빗썸으로 바로 넘어간다 — 코인 차트가 Redis 하나에만 의존하는 단일 장애점이 되지 않는다.
- 일봉·주봉·월봉(MKT-009)은 이번 범위가 아니다 — 계속 빗썸 위임을 유지한다.

**착수 전 선결 과제 (Decision Gate) — 3개 모두 해소됨 (2026-08-06, 이슈 #242)**

- **거래량 확보 — 해소.** 지금 구독 중인 `ticker` 채널은 실제로 확인해보니 개별 체결이 아니라 구독 시 지정한 구간(`tickTypes:["30M"]`)의 롤링 OHLCV 스냅샷이라 우리 용도에 안 맞았다. 대신 **`transaction` 채널**로 구독하면 체결 건별로 `contPrice`(체결가)·`contQty`(체결 수량)·`contDtm`(체결시각)이 온다는 것을 실제 연결로 확인했다 — 이걸로 우리가 직접 OHLCV 1분봉을 만든다. `ticker` 구독은 그대로 유지하고(현재가·주문 트리거 용도) `transaction`을 추가로 구독한다.
- **WebSocket 메시지 포맷 — 해소.** `ticker`·`transaction` 두 채널 모두 실제 연결로 응답 필드를 확인했다. 기존 `BithumbTickerMessageParser`가 쓰던 `content.symbol`·`content.closePrice`·`content.date`·`content.time` 4개는 실측과 정확히 일치했다 — 이슈 #104가 남긴 Decision Gate는 이로써 해소됐다.
- **호출량 실측 — 해소.** §10 레이트리밋 Decision Gate가 요구한 실측을 완료했다: 캐시 구간 안의 요청(30건 시뮬레이션)은 빗썸 호출 0건(**절감률 100%**), 캐싱 없는 대조군은 30건 중 30건(항상 1:1)이었다. 이 100%는 "요청 구간이 이미 수집된(= `since` 이후) 구간 안에 완전히 들어있을 때"의 수치이며, 서버 재시작 직후나 그보다 과거를 포함하는 요청은 그 구간만큼 여전히 빗썸을 호출한다.

**구현 완료 (2026-08-06, `027-crypto-tick-candle-cache`)**

- `BithumbWebSocketFeedClient`가 `transaction` 채널을 추가 구독해 체결을 `CryptoCandleStore`(신설, Redis)에 Lua 스크립트로 원자적으로 누적한다. 거래량은 `×10^8` 정수로 스케일링해 `HINCRBY`로 더한다(코인 수량 소수 8자리 관례, `OrderExecutionService` 등과 동일 기준). 늦게 도착한 체결(이미 지난 분)은 버린다.
- `CachedCryptoCandleProvider`(신설, 데코레이터)가 `interval=1m` 조회에서 `since` 워터마크 기준으로 캐시 구간·빗썸 위임 구간을 나눠 병합한다. Redis 장애 시 전량 위임, 빗썸 장애 시 캐시로 커버되는 요청은 영향 없음.
- 새 MySQL 테이블·마이그레이션 없음(Redis 캐시만). `1d`·`1w`·`1M`은 범위 밖, 그대로 빗썸 위임(MKT-009).
- 동시성 테스트(Testcontainers 실제 Redis, 스레드 50개 동시 체결)로 거래량 유실 0건 확인.

**spec에서 확정한 세부**: `docs/specs/027-crypto-tick-candle-cache/plan.md` 참조 — Redis 키 구조, Lua 원자 갱신, `since` 워터마크로 캐시·위임 구간 분할, TTL 4시간(200봉 상한에서 역산).

- 범위: 1차 고도화. Decision Gate 해소 → spec → 구현 순서로 진행했다.

### 시장가 주문·체결

#### ORD-001 시장가 전량 체결

- 1차의 유일한 주문유형은 `MARKET`이다.
- 요청은 시장, 종목ID, 매수·매도 구분, 수량, `Idempotency-Key`를 포함한다.
- 유효한 최신 가격 한 개로 즉시 전량 체결한다 (주식은 MKT-002의 규칙에 따라 첫 분봉 구간은 시가, 이후는 마감된 마지막 분봉 종가).
- 부분 체결과 슬리피지는 없다.
- 지정가 요청은 `UNSUPPORTED_ORDER_TYPE`으로 거부한다.

#### ORD-002 주문 가능 시간

- 주식은 재생 장중에만 주문 가능하다.
- 주식 장외 주문은 `MARKET_CLOSED`로 거부하고 예약하지 않는다.
- 코인은 최신 시세가 유효하면 24시간 주문 가능하다.

#### ORD-003 주문 검증

- 수량은 0보다 커야 한다.
- 주식 수량은 정수다.
- 코인 수량은 소수점 8자리 이하다.
- 코인 주문금액은 최소 5,000원이다.
- 매수는 거래금액+수수료보다 현금이 많아야 한다.
- 매도는 `availableQuantity = totalQuantity - reservedQuantity`보다 작거나 같아야 한다. 1차 MVP에는 예약 기능이 없어 두 값이 같지만, 2차 OCO·일반 지정가 SELL 도입 뒤 기존 `POST /api/orders` 시장가 SELL도 공통 예약 원장을 반영한다.
- 다른 시장 계좌와 종목을 조합한 요청은 거부한다.

#### ORD-004 수수료와 현금

- 주식 매수·매도 수수료: 거래금액의 0.015%
- 코인 매수·매도 수수료: 거래금액의 0.05%
- 세금은 1차에서 적용하지 않는다.
- 수수료는 원 단위 미만을 내림한다.
- 매수 현금차감 = 거래금액 + 수수료
- 매도 현금증가 = 거래금액 - 수수료

#### ORD-005 FIFO

- 매수 체결마다 잔여수량 lot을 만든다.
- 매도는 실행시각이 가장 빠른 미소진 매수 lot부터 차감한다.
- 하나의 매도가 여러 매수 lot을 소비할 수 있으며 각 배분을 저장한다.
- 실현손익은 배분된 매수원가·매수수수료와 매도금액·매도수수료를 사용한다.
- 체결과 lot 배분 기록은 수정·삭제하지 않는다.

#### ORD-006 멱등성

- 동일 사용자·동일 `Idempotency-Key` 재요청은 추가 체결 없이 최초 응답을 반환한다.
- 같은 키에 다른 요청 본문이 오면 409 `IDEMPOTENCY_CONFLICT`를 반환한다.

### 지정가 주문·체결

> 지정가(LIMIT) 주문은 2026-07-23 결정으로 1차 MVP 범위에서 제외됐고(시장가만), 2차(1차 고도화)에서 다룬다. 체결 방식은 2026-08-03 재확인 결과 배치가 아니라 상시 처리(이벤트 드리븐)로 확정했다. **2차 지정가는 코인을 우선으로 시작하고 주식은 추후 처리한다(2026-08-05 확정)** — 주식은 재생 데이터 기반이라 "이 가격에 도달하면"이라는 지정가 조건 자체가 잘 맞지 않고, 분봉 판정 해상도(미마감 봉의 고가·저가 미노출)·재생세션 종료 시 자동 취소·OCO와의 잠금 순서 공유·**장외 시간에도 지정가 접수를 허용할지(시장가 ORD-002의 `MARKET_CLOSED` 즉시 거부와 의도적으로 달리 갈 결정이었다)** 같은 주식 전용 부가 복잡도가 있다. 아래 LMT-001~005는 코인 전용으로 서술하며, 주식 지정가를 다루게 되면 그 시점에 이 부가 복잡도를 포함해 별도로 정의한다.

#### LMT-001 지정가 주문 생성 (2차 MVP, 코인 전용)

- `POST /api/orders/limit`로 매수·매도 주문에 목표가(지정가)를 지정해 생성한다. **`POST /api/orders`(시장가 전용)의 `orderType="LIMIT"` 422 `UNSUPPORTED_ORDER_TYPE` 거부는 그대로 유지한다** — 두 경로가 영구히 공존하며 시장가 경로에 지정가를 흡수하지 않는다(이미 완료된 시장가 로직의 회귀 회피가 경로 분리의 목적이므로).
- 시장가와 달리 즉시 체결되지 않고 `PENDING` 상태로 대기한다.
- 매수는 `수량 × 지정가 + 예상 수수료`만큼 현금을, 매도는 수량을 각각 예약(에스크로)한다 — 예약 없이 검증만 하면 같은 잔고로 여러 지정가 주문을 낼 수 있는 이중사용이 가능해진다.
- 코인 지정가 SELL은 holding 예약 원장을 사용해 공통 `availableQuantity`를 차감하고, 기존 시장가 SELL도 이 예약분을 제외한 수량만 매도할 수 있다. (주식 전용 OCO exit plan은 별도 시장·종목이라 이 예약 원장과 겹치지 않는다 — §3단계 투자 실습 참고)
- **배포 순서(2026-08-05 확정)**: 코인 지정가 SELL은 공통 예약 원장과 기존 시장가 SELL의 `availableQuantity` 검증이 먼저 배포됐거나 같은 atomic release에 포함된 경우에만 활성화한다 — 원장 없이 지정가 SELL만 먼저 켜지면, 원장을 모르는 기존 시장가 SELL이 지정가로 예약된 수량을 중복으로 매도할 수 있다.
- 예약 가능한 현금·수량이 부족하면 거부한다(오류 원칙은 시장가 ORD-003의 현금·수량 부족과 동일하게 재사용).
- 매수 지정가가 현재가 이상이거나 매도 지정가가 현재가 이하로 즉시 조건을 충족하는 주문도 거부하지 않는다 — 생성 직후 다음 가격 갱신 트리거(LMT-002)에서 그대로 체결된다. 체결가는 여전히 지정가로 고정되므로 시장가보다 유리하게 체결될 수 있다.
- **유효기간**: 코인은 24시간 시장이라 만료 없이 유지된다(GTC). 취소는 LMT-003으로만 가능하다.

#### LMT-002 지정가 체결 트리거 (2차 MVP, 코인 전용)

- 체결은 배치(주기적 일괄 처리)가 아니라 **상시 처리(이벤트 드리븐)**로 확정한다 (2026-08-03, 기존 "배치" 결정에서 재변경 — "기존 명세 변경표" 지정가 체결 행도 함께 갱신).
- 트리거는 빗썸 웹소켓 수신으로 내부 가격 모델이 갱신되는 시점이다.
- 체결 판정은 매수 `현재가 ≤ 지정가`, 매도 `현재가 ≥ 지정가`다. 웹소켓 틱 단위로 판정하므로 목표가를 통과하는 순간을 놓치지 않는다.
- 목표가 도달 시 체결가는 목표가로 고정한다(슬리피지 없음) — 예약해둔 금액·수량과 정확히 일치시켜 잔고 이동을 단순하게 유지한다.
- 체결 시 예약을 실제 현금·보유수량 이동으로 확정하고 `status`를 `FILLED`로 변경한다.
- **동시성 제어(2026-08-05 확정, 2026-08-05 `docs/specs/015-limit-order` 구현·검증 중 잠금 순서 정정)**: 동시 체결 경합(같은 주문에 대해 가격 갱신 이벤트가 겹쳐 도착하는 경우)과 취소(LMT-003) 요청·체결 트리거가 동시에 도착하는 경합은 **비관적 락(`SELECT ... FOR UPDATE`)으로 잠그는 방식으로 제어한다.** 지정가 체결은 매수·매도 어느 쪽이든 `accounts`(현금)와 `holdings`(보유수량) 행을 함께 건드리므로, 잠금 순서는 **`order → account → holding`**으로 고정한다. order 락을 가장 먼저 잡는 이유는 "같은 주문에 대한 중복 이벤트(그리고 LMT-003 취소 요청)"만 배제하는 용도라 그 주문 자신의 row 외에는 아무와도 경쟁하지 않기 때문이다(식별 자원을 가장 먼저 잠근다는 점에서 주식 전용 OCO가 `replay session`을 가장 먼저 잠그는 것과 형태가 같다). 여러 트랜잭션이 실제로 경합하는 자원은 `account`·`holding`뿐이며, 이 둘의 순서는 어떤 흐름에서도 항상 account가 먼저다. 신규 종목 첫 매수처럼 holding 행이 아직 없는 경우는 그 단계를 건너뛰고 `order → account` 순으로 잠근다. 먼저 락을 획득한 트랜잭션의 커밋이 확정된 뒤, 나중 트랜잭션은 갱신된 `status`를 보고 스스로 거부(이미 `FILLED`면 취소 거부)·no-op(이미 취소됐으면 체결 skip) 처리하므로 예약 이중 반환이나 체결 후 취소 같은 경합은 발생하지 않는다. **기존 시장가 체결(`OrderExecutionService`)은 매수가 `account → holding`, 매도가 `holding → account` 순으로 행을 건드렸으나, 실제로 경합하는 account·holding에 대해 매도 경로가 이 전역 순서에 맞춰 `account`를 먼저 잠그도록 조정했다(구현 현황은 §3 참고).** 매수 경로는 이때 계좌 락·holdings 락이 모두 없는 상태였다가, 이후 이슈 #224로 같은 전역 순서(`account`를 `holding`보다 먼저 잠금)에 맞춰 계좌·holdings 비관적 락을 보강했다 — 그렇지 않으면 동시 매수·매도 체결이 반대 순서로 잠금을 시도하는 전형적인 ABBA 데드락이 된다.
- **큐 적재·소비 방식(2026-08-05 확정)**: 별도 메시지 큐 없이 **인메모리 Spring `ApplicationEvent`**로 처리한다. 다만 랭킹(`RankingEventListener`)의 `@TransactionalEventListener(AFTER_COMMIT)`을 그대로 옮겨오지는 않는다 — 랭킹은 매도 체결이라는 **DB 트랜잭션 안에서** 이벤트를 발행하므로 커밋 이후로 미룰 대상이 있지만, 코인 웹소켓 시세 수신(빗썸 틱)은 애초에 DB 트랜잭션이 아니라서 커밋을 기다릴 대상이 없다. 그래서 가격 갱신 이벤트는 일반(`AFTER_COMMIT`이 아닌) 리스너가 즉시 받아 `PENDING` 지정가 주문의 체결 조건을 평가하고, 조건이 충족되면 그 주문 1건을 잠가(§동시성 제어) 별도 트랜잭션으로 체결을 확정한다. **랭킹과 같은 패턴인 지점은 "체결 트랜잭션이 커밋된 이후에 후속 이벤트(예: NOTI-001 알림)를 발행한다"는 부분이다** — 여기서는 체결 자체가 DB 트랜잭션이므로 `AFTER_COMMIT`이 그대로 적용된다. 후속 고도화에서 Redis/Kafka 등 외부 큐로 교체할 수 있도록 리스너 경계는 열어둔다.
- **Decision Gate**: 부분체결·슬리피지 허용 여부는 이번 범위에 포함하지 않는다(전량 목표가 체결만). 외부 큐 도입 여부·순서 보장 강화는 착수 시 `docs/specs/015-limit-order`에서 필요성이 확인되면 별도로 확정한다.

#### LMT-003 지정가 주문 취소 (2차 MVP, 코인 전용)

- `DELETE /api/orders/{orderId}`로 본인 소유의 `PENDING` 지정가 주문을 취소한다.
- 취소 시 예약해둔 현금(매수) 또는 수량(매도)을 반환한다.
- 이미 `FILLED`이거나 이미 취소된 주문의 취소 요청은 거부한다. 체결 트리거(LMT-002)와의 동시 도착 경합 제어는 LMT-002의 동시성 제어 항목을 참고한다.

#### LMT-004 미체결 주문 목록 (2차 MVP, 코인 전용)

- `GET /api/orders/pending?market=&cursor=&limit=`로 본인 소유의 `PENDING` 상태 지정가 주문만 조회한다. `market`은 계좌·거래내역 조회 규칙(PORT-002)과 동일하게 필수이며 시장별로 나눠 조회한다.
- 커서 기반 페이지네이션을 사용하며 최신순으로 정렬한다.
- 다른 사용자의 미체결 주문은 조회할 수 없다.

#### LMT-005 지정가 주문 수정 (2차 MVP, 코인 전용)

> 2026-08-06 확정. LMT-001~004 완료 시점까지 `docs/specs/015-limit-order/spec.md`가 "주문 수정(가격·수량 변경) — 취소 후 재생성으로 대체한다. 필요성이 확정되면 별도 이슈로 다룬다"로 제외해둔 항목이며, 이번에 그 필요성을 확정해 정식 요구사항으로 반입한다.

- `PATCH /api/orders/{orderId}`로 본인 소유의 `PENDING` 지정가 주문의 지정가(`limitPrice`)와 수량(`quantity`)을 변경한다. 둘 다 변경할 수 있고 한쪽만 보내는 것도 허용한다.
- **취소 후 재생성으로 대체하지 않는 이유(원자성)**: 지금도 `DELETE /api/orders/{orderId}` → `POST /api/orders/limit` 두 번 호출로 수정을 흉내낼 수 있으나, 취소가 성공한 뒤 재생성이 실패하면 **주문이 사라진 채로 끝난다.** 매수 지정가를 올리면 반환된 예약 현금으로 부족해 409 `INSUFFICIENT_CASH`가, 매도 수량을 늘리면 409 `INSUFFICIENT_QTY`가 날 수 있어 실제로 발생 가능한 경로다. 수정은 한 트랜잭션에서 처리하고 **예약 재계산이 실패하면 원주문을 변경 전 상태 그대로 유지한다** — 이 원자성이 이 요구사항의 존재 이유다.
- **주문 식별자는 유지한다** — 같은 `orderId`의 `orders` 행을 갱신하며 새 주문을 만들지 않는다. `requestedAt`도 유지하므로 미체결 목록(LMT-004)에서 주문의 정렬 위치가 바뀌지 않는다.
- **예약 재계산**: 매수는 변경 전 예약(`수량 × 지정가 + 예상 수수료`)을 해제하고 변경 후 값으로 다시 예약한다. 매도는 변경 전 예약 수량을 해제하고 변경 후 수량으로 다시 예약한다. 예약 가능한 현금·수량이 부족하면 LMT-001 생성과 같은 오류(409 `INSUFFICIENT_CASH`·`INSUFFICIENT_QTY`)로 거부하고 주문은 변경 전 상태로 남는다.
- **검증 순서는 LMT-003(취소)과 동일하게 존재(404) → 소유(403) → 상태(409)로 고정한다.** 이미 `FILLED`이거나 이미 취소된 주문의 수정 요청은 거부한다.
- **동시성 제어**: 체결 트리거(LMT-002)·취소(LMT-003)와의 동시 도착 경합은 LMT-002의 잠금 순서(`order → account → holding`)를 그대로 따른다. 대상 주문을 가장 먼저 잠그므로 수정-대-체결, 수정-대-취소 경합도 같은 방식으로 직렬화된다.
- **`Idempotency-Key` 헤더는 요구하지 않는다** — 변경 후 값을 절대값으로 지정하는 요청이라 같은 요청을 두 번 보내도 결과가 같다(자연 멱등). 같은 이유로 헤더를 요구하지 않는 `DELETE /api/orders/{orderId}`(LMT-003)와 형태가 같다.
- 수정 결과가 즉시 체결 조건을 충족하는 값이어도 거부하지 않는다 — LMT-001 생성과 동일하게 다음 가격 갱신 트리거(LMT-002)에서 그대로 체결된다.
- **수정 이력은 저장하지 않는다** — `orders` 행을 덮어쓰며 변경 전 지정가·수량은 남기지 않는다(신규 컬럼·마이그레이션 없음). 이력 조회 요구가 생기면 그 시점에 별도로 다룬다.
- 주식 지정가 수정은 주식 지정가(LMT-001~004)와 같은 이유로 범위 밖이다. 시장가 주문 수정도 범위 밖이다 — 시장가는 생성 즉시 `FILLED`라 `PENDING` 상태를 갖지 않으므로 수정 대상이 될 수 없다(LMT-003 취소가 시장가를 자연히 배제하는 것과 같은 구조다).

**계좌·보유 조회 계약 영향 — 해소됨(2026-08-06, 이슈 #235)**: 예약(에스크로)이 도입되면 `cashBalance`(ACCT-002, 계좌 원장 값 그대로)와 실제 "주문 가능 금액"이 갈라지고, 보유수량(PORT-001)도 "총 보유"와 "주문 가능 수량"이 갈라진다. `AccountSummaryResponse`에 `reservedCash`(long, `cashBalance` 다음 위치)를, `HoldingListItemResponse`에 `reservedQuantity`(BigDecimal, `quantity` 다음 위치)를 추가해 원장 값을 그대로 노출하는 것으로 확정했다 — 둘 다 이미 존재하는 원장 값(`accounts.reserved_cash`·`holdings.reserved_quantity`, V22)을 노출할 뿐 새로운 계산·집계는 도입하지 않는다. "주문 가능 금액"·"주문 가능 수량" 같은 파생값 필드(`availableCash`/`availableQuantity`)는 추가하지 않는다 — 클라이언트가 `cashBalance - reservedCash`·`quantity - reservedQuantity`로 직접 계산할 수 있다.

LMT-001~005의 상세 계약(요청·응답 필드, 전체 오류 코드)은 `docs/specs/015-limit-order/spec.md`에서 확정했다. LMT-001~005 전부 완료돼 `OrderType.LIMIT`·`OrderStatus.PENDING`을 포함한 지정가 경로가 코드에 존재한다(LMT-005는 PR #240, 이슈 #239). `PATCH /api/orders/{orderId}` 요청 필드·부분 갱신 허용 형태·오류 코드는 `docs/api-routes.md`·`docs/api-contracts.md`에 함께 반영됐다.

### 알림

> 알림은 2026-07-23 결정으로 1차 MVP 범위에서 제외됐고, 2차(1차 고도화)에서 다루기로 했었다. 범위를 지정가 체결 알림으로 한정하고 SSE 실시간 push를 포함하는 것으로 2026-08-04 확정했다(이슈 #140). 알림 목록·읽음 처리·삭제(NOTI-003~005)는 이슈 #140에는 없었으나 Notion API 명세서(정본) "알림" 그룹에 이미 정의돼 있어 2026-08-04 함께 반입했다. 지정가(LMT-001~004)가 코인 전용으로 시작하므로(2026-08-05 확정) 알림도 사실상 코인 지정가 체결에서만 발생한다 — 주식 지정가를 다루게 되는 시점에 알림 대상도 함께 넓어진다. **2026-08-07: 착수 시점을 2차(1차 고도화) → 3차(2차 고도화)로 재조정했다(이슈 #261)** — 위 범위·전달 방식 확정 내용은 변경 없이 그대로 유지되며, 팀 일정상 착수 순서만 뒤로 밀렸다.

#### NOTI-001 지정가 체결 알림 생성 (3차 MVP)

- 지정가 매수·매도 주문이 체결됐을 때만 알림을 생성한다 — 시장가 주문은 요청에 대한 응답으로 체결 여부를 즉시 확인할 수 있어 알림 대상이 아니다.
- 알림 생성은 클라이언트가 호출하는 별도 API가 아니라, 지정가 체결 트리거(LMT-002) 로직 내부에서 함께 처리되는 서버 내부 동작이다.
- 생성된 알림은 NOTI-002로 실시간 push하는 것과 별개로 저장된다 — NOTI-003(목록)·NOTI-004(읽음)·NOTI-005(삭제)가 이후에도 알림을 조회·조작할 수 있어야 하므로, push 시점에 연결이 끊겨 있어도 알림 자체는 남는다.

#### NOTI-002 알림 전달 — SSE 실시간 push (3차 MVP)

- NOTI-001에서 생성된 알림은 SSE 실시간 push로 전달한다 — 지정가 체결은 본인 자산(현금·보유수량)이 즉시 바뀌는 개인 이벤트라 지연 없이 알아야 하므로, 랭킹(RANK-001)이 REST 조회를 선택하며 SSE를 제외한 것과 반대로 SSE를 채택한다.
- 알림 대상은 해당 지정가 주문을 낸 본인 회원으로 한정한다 — 다른 회원에게는 전달하지 않는다.

#### NOTI-003 알림 목록 조회 (3차 MVP)

- `GET /api/notifications?cursor=&limit=`로 본인의 알림 목록을 조회한다.
- `market` 파라미터는 없다 — 거래내역(PORT-002)·미체결 주문(LMT-004)·랭킹(RANK-001)은 시장별 데이터가 커서 시장별로 나눠 조회하지만, 알림은 개별 체결 사건에 대한 통지라 시장별로 나눌 실익이 적어 주식·코인을 한 목록에서 통합 조회한다(Notion API 명세서 그대로).
- 커서 기반 페이지네이션을 사용하며 최신순으로 정렬한다.
- 다른 회원의 알림은 조회할 수 없다.
- 알림 상세 조회 API는 두지 않는다 — 알림을 클릭하면 포트폴리오로 이동하고, 실제 체결 정보(수량·가격·평가손익 등)는 포트폴리오 조회(PORT-001)가 최신 값으로 제공하므로 알림 자체는 목록에 필요한 최소 정보만 담으면 된다.
- 각 알림 항목에는 클릭 시 이동할 대상을 식별할 수 있는 시장(market)·종목(instrumentId) 정보가 포함된다 — PORT-001이 market·instrumentId 단위로 보유자산을 모으므로 이 둘이면 해당 종목의 포트폴리오 항목으로 바로 이동할 수 있다. 거래id(tradeId)는 포함하지 않는다 — 이동 대상이 거래내역(PORT-002)이 아니라 포트폴리오이므로 지금은 쓰이지 않는다. 정확한 응답 필드명은 Decision Gate에서 확정한다.
- 알림이 가리키는 종목을 이후 전량 매도해 보유목록에서 제거된(PORT-001) 뒤에도 알림 자체는 남아 있을 수 있다 — 이 경우 클릭하면 해당 종목을 하이라이트하지 않고 포트폴리오 화면으로만 이동한다(오류나 빈 상태 안내를 별도로 두지 않는다).

#### NOTI-004 알림 읽음 처리 (3차 MVP)

- `POST /api/notifications/{id}/read`로 알림 하나를 읽음 처리한다.
- `POST /api/notifications/read-all`로 본인의 모든 알림을 한 번에 읽음 처리한다.
- 다른 회원의 알림은 읽음 처리할 수 없다.

#### NOTI-005 알림 삭제 (3차 MVP)

- `DELETE /api/notifications`로 본인의 모든 알림을 한 번에 삭제한다.
- `DELETE /api/notifications/{id}`로 알림 하나를 삭제한다.
- 다른 회원의 알림은 삭제할 수 없다.

**Decision Gate**: SSE 연결·인증 스코프·재연결 처리, 알림 페이로드·응답 필드, 알림 저장 스키마, 전체 오류 코드, `docs/api-routes.md`·`docs/api-contracts.md` 갱신은 착수 시 알림 spec에서 확정한다.

상세 계약은 3차 착수 시 알림 spec에서 확정한다. **알림 spec 폴더는 아직 만들지 않았고 번호도 배정되지 않았다** — 이전 서술이 가리킨 `docs/specs/013-notification`은 존재하지 않으며 `013`은 캔들 기간 확장(`013-candle-interval`)이 이미 점유하고 있다(2026-08-04 정정). 착수 시점에 비어 있는 번호를 실제 폴더 목록으로 재확인해 배정한다 — 번호를 미리 지어내지 않는다.

### 보유자산과 거래내역

#### PORT-001 보유자산 (2026-07-30 현재가·수익률 필드 반영, 이슈 #52)

- 계좌·종목별 보유수량과 평균단가를 제공한다.
- 현재가·평가금액·미실현손익·수익률은 최신 시세로 계산한다.
- 전량 매도한 종목은 활성 보유목록에서 제거한다.

#### PORT-002 거래내역

- 사용자 본인의 시장별 체결내역을 최신순으로 조회한다.
- 체결가격·수량·거래금액·수수료·체결시각을 반환한다.
- 매도 내역은 FIFO 실현손익을 반환한다.
- `market`은 필수 파라미터다. 여러 시장을 합쳐 조회하는 통합 조회는 1차 범위에 없다 (2026-07-28 팀 결정 — 주식·코인은 항상 나눠서 조회한다).

#### PORT-003 주문 목록 (2026-08-04 market 필수·페이지네이션 정책 확정, 이슈 #177)

- 사용자 본인의 주문 요청을 최신순으로 조회한다.
- 주문 목록은 주문 구분(매수·매도), 종목, 요청수량, 주문유형, 주문상태, 주문요청시각을 반환한다.
- 체결가격·체결금액·수수료·실현손익·체결시각은 주문 목록이 아니라 `PORT-002 거래내역`에서 반환한다.
- 1차 주문은 시장가 즉시 전량 체결이므로 정상 주문의 상태는 `FILLED`다. 지정가·미체결·부분체결·취소 상태는 1차 범위에 없다.
- 다른 사용자의 주문은 조회할 수 없다.
- `market`은 필수 파라미터다. `PORT-002 거래내역`과 동일하게 시장별로 나눠 조회하며, 통합 조회는 지원하지 않는다 — 2차 지정가 도입(`GET /api/orders/pending`, LMT 요구사항)으로 미체결 주문이 계속 쌓이는 상황에 대비해 형제 API(PORT-002·RANK-001·JOUR-006·`GET /api/orders/pending`)와 조회 패턴을 통일한다.
- 커서 기반 페이지네이션(`cursor`·`limit`)을 도입한다. `limit`은 선택이며 기본 20, 1~100 범위를 벗어나면 400 `VALIDATION_ERROR`로 거부한다(클램핑 없음) — `GET /api/trades`(PORT-002)와 동일한 검증 방식이다.
- 이 정책은 1차 MVP 당시의 "별도 페이지네이션을 두지 않는다"는 가정을 대체한다. 실제 코드(`OrderController`·`OrderService`·`OrderRepository`) 변경과 `docs/api-routes.md`·`docs/api-contracts.md` 갱신은 이 결정과 별개의 구현 이슈에서 진행한다.
- 이미 배포된 `GET /api/orders` 응답 계약을 바꾸는 breaking change다 — 실제 구현 시 프론트엔드(FinPlay 레포)와 사전 조율이 필요하다.

> 투자일기(매수·매도 회고) 기능은 2026-07-28 Notion 확인 결과 1차 MVP가 아니라 2차(1차 고도화) 범위다. 1차 요구사항에서는 제외하며, 아래 JOUR 요구사항은 2차 착수 전 확정한 내용이다.

### 투자일기

#### JOUR-001 매수 회고 작성 (2차 MVP)

- `POST /api/trades/{buyTradeId}/journal`로 본인 소유 매수 체결 1건에 투자일기(회고) 1건을 작성한다.
- `buyTradeId`가 존재하는 매수 체결이며 인증 사용자 본인 소유인지 검증한다. 없는 체결은 404, 타인 체결은 403, 매수가 아닌 체결은 400으로 거부한다.
- 매수 체결 1건당 투자일기 1건만 존재한다(`UNIQUE(buy_trade_id)`). 이미 존재하면 409 `DUPLICATE_RESOURCE`로 거부한다.
- 본문은 공백이 아닌 텍스트여야 한다. 목표가·손절가·예상보유기간 같은 구조화 필드는 이번 범위에 포함하지 않는다.
- 성공 시 투자일기 ID, 매수 체결 ID, 본문, 작성시각을 반환한다.
- 작성 성공·실패는 주문·체결·계좌·잔액·보유·FIFO lot·실현손익(ORD-005)을 변경하지 않는다.

#### JOUR-002 매수 회고 수정 (2차 MVP)

- `PATCH /api/trades/{buyTradeId}/journal`로 본인이 작성한 매수 회고 본문을 수정한다.
- 소유권·존재 검증은 JOUR-001과 동일한 오류 매핑(404·403)을 따른다. 매수가 아닌 체결에 대한 요청은 400으로 거부한다.
- 해당 매수 체결에 회고가 아직 없으면 404로 거부한다 — 수정 요청으로 새 회고를 만들지 않는다(upsert 금지).
- 수정 가능한 필드는 본문(`content`) 하나이며 작성(JOUR-001)과 같은 검증을 받는다. 성공 시 투자일기 ID, 매수 체결 ID, 본문, 작성시각, 수정시각을 반환한다.
- 수정 성공·실패는 주문·체결·계좌·잔액·보유·FIFO lot·실현손익을 변경하지 않는다.
- **수정 잠금을 두지 않는다 (2026-08-04 확정, 이슈 [#197](https://github.com/finplay-team/finplay/issues/197)).** 매도 배분 여부와 무관하게 본인 매수 회고는 횟수 제한 없이 수정할 수 있다. 이 결정은 **이전의 Decision Gate와 `docs/specs/005-order-sell/spec.md`의 "첫 매도 배분이 발생한 매수 lot은 투자일기 수정이 잠긴다"는 규칙을 대체한다.** 근거는 셋이다.
  1. **잠금이 보호할 소비자가 없다.** 투자일기 본문을 실제로 읽어 쓰는 기능이 현재 하나도 없다 — 랭킹(RANK-001)은 `accounts.realized_pnl` 원장 기준이라 투자일기와 무관하고, AI 피드백·주간/월간 리포트는 미구현이다. 소비자가 없는 상태의 보호 장치는 가상의 요구사항을 위한 설계다.
  2. **"첫 매도 배분"은 틀린 트리거였다.** 사후 왜곡(hindsight bias)은 매도 여부와 무관하게 생긴다 — 팔지 않고 보유만 해도 주가 변동을 보며 회고를 다시 쓸 수 있다. `trade_allocations` 기반 잠금은 실제 위협과 상관없는 대리 신호였다.
  3. **되돌리기 비용이 비대칭이다.** "잠금 없음 → 잠금 추가"는 오류 코드를 더하는 확장이지만, "잠금 있음 → 해제"는 이미 거부되던 요청이 성공하게 되는 완화라 프론트엔드가 대응해 둔 오류 경로를 걷어내야 한다. JOUR-004(매도 회고 수정)가 같은 근거로 잠금 없이 확정됐다.
- **~~향후 재검토 조건~~ → 2026-08-16 해제, 결론은 "잠금 없음 유지"다.** 이 자리는 "투자일기 본문을 읽어 분석하는 기능(spec 012)이 붙는 시점에 그 기능의 스펙에서 피드백 생성 시점 기준 수정 제한을 정의한다"는 방향을 남겨 두었다. 실제로 그 기능이 붙으면서(`docs/specs/012-ai-feedback/spec.md` §FEED-013 결정 2) **잠금 대신 재생성**을 택했다 — 일기가 바뀌면 피드백을 다시 만든다. 예고했던 "잠금 트리거를 피드백 생성 이벤트에 묶는다"는 방향 자체가 사용자 동선과 어긋난다는 것이 이유이며(회고는 대개 피드백을 본 **뒤에** 쓴다), 그래서 `JOURNAL_LOCKED` 오류 코드는 **앞으로도 만들지 않는다.** 매수 회고 수정은 계속 횟수·시점 제한이 없다.

#### JOUR-003 매도 회고 작성 (2차 MVP)

- `POST /api/trades/{sellTradeId}/sell-journal`로 본인 소유 매도 체결 1건에 투자일기(회고) 1건을 작성한다.
- 소유권·존재·중복 검증은 JOUR-001과 동일한 원칙(404·403·409)을 매도 체결 기준으로 적용한다. 매도가 아닌 체결에 대한 요청은 400으로 거부한다.
- 본문 형식과 구조화 필드 제외 원칙은 JOUR-001과 동일하다.

#### JOUR-004 매도 회고 수정 (2차 MVP)

- `PATCH /api/trades/{sellTradeId}/sell-journal`로 본인이 작성한 매도 회고 본문을 수정한다.
- 소유권·존재 검증은 JOUR-003과 동일한 오류 매핑을 따른다.
- 매도 회고는 매도 이후의 기록이므로 후속 매도 배분에 의한 잠금 대상이 아니다. 그 외 잠금 조건도 두지 않기로 착수 시 확정했다(이슈 #190, `docs/specs/007-journal/spec.md` §비즈니스 규칙). 매수 회고(JOUR-002)도 2026-08-04 이슈 #197에서 같은 결론에 도달했으므로, **현재 두 회고 모두 수정 잠금이 없다.**

#### JOUR-005 투자일기 상세 조회 (2차 MVP)

- **`GET /api/journal/buy/{buyTradeId}`로 매수 회고를, `GET /api/journal/sell/{sellTradeId}`로 매도 회고를 단건 조회한다.**
- 본인이 작성한 투자일기만 조회할 수 있다. 체결이 존재하지 않으면 404, 타인 소유면 403으로 거부한다. 체결은 있으나 회고가 아직 없으면 404, 경로와 체결 구분이 어긋나면(매수 경로에 매도 체결 ID 등) 400이다 — 검증 순서·오류 매핑은 수정 계약(JOUR-002·004)과 같다.
- 응답은 `journalId`·해당 체결 ID·`content`·`createdAt`·`updatedAt` 5필드이며 수정 응답과 구성이 같다. 조회는 읽기 전용이고 스키마를 바꾸지 않는다.
- **식별자 체계 Decision Gate는 2026-08-05 이슈 [#217](https://github.com/finplay-team/finplay/issues/217)에서 "타입별 경로 분리"로 해제됐다. 이 결정이 위의 URL을 확정하며, 초안이었던 단일 경로 `GET /api/journal/{journalId}`를 대체한다.** 근거는 셋이다.
  1. **숫자 하나로는 어느 테이블인지 정해지지 않는다.** `buy_trade_journals.id`와 `sell_trade_journals.id`는 서로 다른 AUTO_INCREMENT 시퀀스라 값이 겹친다.
  2. **두 테이블 통합은 이미 머지된 JOUR-001~004 구현(PR #181·#189·#192·#201)과 마이그레이션을 되돌려야 한다.** 상세 조회 하나를 위해 치를 비용이 아니다.
  3. **접두사 문자열 ID(`"buy:42"`)는 모든 리소스 ID가 숫자인 이 프로젝트의 유일한 예외가 된다.**
  - 목록(JOUR-006)이 이미 항목마다 `journalType` + 원래 체결 ID를 내려주므로, 클라이언트는 목록에서 받은 타입만 보고 어느 경로로 갈지 안다. **목록 응답 계약은 이 결정으로 바뀌지 않는다** — 통합 `journalId`는 앞으로도 노출하지 않는다.
  - 상세 계약과 설계는 `docs/specs/007-journal/spec.md` §비즈니스 규칙 "상세 조회는 타입별 경로로 분리한다"·`plan.md` §JOUR-005가 정본이다.

#### JOUR-006 투자일기 목록 조회 (2차 MVP)

- `GET /api/journal?market=&cursor=&limit=`로 본인의 투자일기 목록을 조회한다.
- `market`은 계좌·거래내역 조회 규칙(PORT-002)과 동일하게 시장별로 나눠 조회하며, 통합 조회는 이번 범위에 없다.
- 커서 기반 페이지네이션을 사용하며 최신순으로 정렬한다.
- 다른 사용자의 투자일기는 조회할 수 없다.
- **2026-08-04 착수 확정 (이슈 [#203](https://github.com/finplay-team/finplay/issues/203), `docs/specs/007-journal/spec.md` §비즈니스 규칙 "투자일기 목록 조회")** — ① 매수·매도 회고를 한 목록에 섞어 반환하고, 항목은 `journalType`(`BUY`|`SELL`) + 원래 체결 ID로만 식별한다. **통합 `journalId`를 노출하지 않아 JOUR-005의 식별자 Decision Gate를 선점하지 않는다.** ② 최신순의 기준은 `createdAt`(회고 작성 시점)이며 동시각은 체결 ID로 끊는다 — `updatedAt` 기준이면 방금 수정한 오래된 회고가 맨 위로 올라와 "최신순"이 흔들린다. ③ `limit`은 기본 20·1~100이며 범위 밖은 클램핑 없이 400 `VALIDATION_ERROR`다(PORT-002·PORT-003과 동일). ④ 이 목록은 스키마를 바꾸지 않는다 — 기존 두 회고 테이블을 읽기만 한다.

투자일기 기반 AI 피드백·복기, 목표가·손절가 등 구조화 필드는 위 6개 요구사항 어디에도 포함하지 않는다. 상세 계약(전체 오류 코드, 응답 필드)은 2차 착수 시 `docs/specs/007-journal/spec.md`에서 확정한다.

### 랭킹

> 수익 랭킹은 2026-07-23 결정으로 1차 MVP 범위에서 제외됐고, 2차(1차 고도화)에서 다룬다. 시장별(STOCK/CRYPTO) 분리, Redis ZSET 순위 관리, REST 조회 방식은 2026-08-03 확정했다(이슈 #139). 아래 RANK-001·RANK-002는 그 확정에 따라 2차 착수 전 정의한 정책이다.

#### RANK-001 전체 랭킹 조회 (2차 MVP)

- `GET /api/rankings?market=&limit=`로 실현손익 기준 전체 랭킹을 시장별로 조회한다.
- `market`은 필수 파라미터다 — 계좌·거래내역 조회 규칙(PORT-002)과 동일하게 시장별로 나눠 조회하며, 두 시장을 합산한 통합 랭킹은 제공하지 않는다.
- `limit`은 생략 시 기본 10건을 반환한다. 요청값이 50을 초과해도 서버는 최대 50건까지만 반환한다(클램핑) — 남용 방지를 위한 상한이다. 1 미만(0 이하)이어도 오류로 거부하지 않고 기본값 10으로 클램핑한다 — 상한과 동일한 근거(사용성 우선)를 하한에도 일관되게 적용한 것이다. 이는 범위 밖 값을 400 `VALIDATION_ERROR`로 거부하는 거래내역 조회(`GET /api/trades`, `docs/api-contracts.md`)와 의도적으로 다른 처리다 — 거래내역은 완전성이 중요한 감사성 조회라 누락 없이 정확한 건수를 보장해야 하지만, 랭킹은 "상위 N명 탐색" 용도라 과도한 요청값을 오류로 막을 필요 없이 상한까지만 보여줘도 사용성에 문제가 없다.
- 매도 체결 이력이 한 번도 없는 회원은 랭킹 대상에서 제외한다 — 매도 이력이 있고 그 결과 실현손익이 정확히 0인 회원은 제외 대상이 아니며 다른 회원과 동일하게 동점 규칙을 적용한다. 이력 없는 회원까지 포함하면 대다수가 0으로 동점 처리되어 랭킹의 의미가 없어지므로, 제외 기준은 손익 값이 아니라 매도 이력 유무다.
- 정렬 기준은 시장별 실현손익 내림차순이다.
- 동점자(실현손익이 완전히 같은 회원)는 공동 순위를 부여한다 — 다음 순위는 동점자 수만큼 건너뛴다(예: 공동 1위 2명 다음 순위는 3위). **Redis ZSET의 기본 순위 커맨드는 동점이어도 멤버를 사전순으로 순차 배정해 이 규칙과 다르게 동작하므로, 공동 순위 계산은 애플리케이션 계층에서 별도로 처리한다** — score desc·동점자는 userId asc로 재정렬한 뒤 고유 score마다 `countStrictlyGreater(score) + 1`로 순위를 계산한다(RANK-001 구현 완료, PR #196).
- 순위는 Redis ZSET(시장별 키)으로 관리하며, score는 시장별 계좌 행(`accounts`, ACCT-001 `UNIQUE(user_id, market)`)의 `realized_pnl` 컬럼 값을 그대로 쓴다 — 매도 체결로 `accounts.realized_pnl`이 갱신될 때(ORD-005 FIFO 배분 확정 시점) **커밋 이후(after-commit)** 해당 회원의 ZSET score를 갱신한다(이벤트 드리븐) — 지정가 체결 트리거(LMT-002)와 동일하게 배치가 아닌 상시 처리다. Redis는 MySQL 트랜잭션에 참여하지 않으므로 커밋 전에 갱신하면 롤백 시 Redis에만 반영이 남을 수 있어, 반드시 커밋이 확정된 뒤에 갱신한다. 매 조회마다 `trades`를 재집계하지 않는다.
- **다중 인스턴스 환경에서의 score 갱신 경합(2026-08-05 확정)**: 별도 분산락 없이 **단순 덮어쓰기(ZADD)로 처리한다** — 갱신 시점마다 MySQL `accounts.realized_pnl`의 최신 절댓값을 다시 읽어 그대로 ZADD하므로(증분이 아니라 절대값 대입), 여러 인스턴스의 갱신 이벤트가 어떤 순서로 도착해도 결과적으로 DB 값과 일치하는 상태로 수렴한다. 순서가 뒤바뀌어 잠깐 stale한 score가 보일 수 있으나 다음 매도 체결 시 다시 절대값으로 덮어써 자연 정정된다.
- 실현손익의 정본은 MySQL `accounts.realized_pnl`이며 Redis ZSET은 조회 성능을 위한 파생 데이터다. Redis 유실 시 MySQL 원장으로 재구성한다. 커밋 이후 갱신 자체가 실패하는 경우(예: 그 순간 Redis 장애)는 재시도(backoff) 후에도 실패하면 로그만 남기고 매도 체결 자체에는 영향을 주지 않는다.
- **(2026-08-09 확정, 이슈 #279) 재구성 트리거·절차**: 트리거는 두 가지이며 둘 다 같은 경로를 탄다 — 애플리케이션 **기동 완료 시점 1회**와 **매일 04:20(KST) 정기 배치**다. 수동 재구성 엔드포인트는 만들지 않는다(관리자 롤 개념이 없어 범위가 넓어진다). 절차는 항상 **전체 재구성**이다: 해당 시장 계좌 중 `trades.side = 'SELL'` 체결 이력이 있는 계좌를 대상으로 뽑고(**`realized_pnl` 값이 아니라 매도 이력 유무가 기준**이다 — 손익이 정확히 0인 매도 계좌가 빠지면 재구성 결과가 유실 전과 달라진다), 그 계좌들의 `accounts.realized_pnl`을 score로 임시 키에 적재한 뒤 원자적으로 교체한다. 재구성은 **읽기 전용**이라 주문·체결·계좌·보유 원장을 일절 변경하지 않는다. 멱등하고 단일 인스턴스 전제라 분산 락은 두지 않는다 — 다중 인스턴스로 전환하면 임시 키 충돌과 중복 부하를 재검토한다.
- **(2026-08-09 확정, 이슈 #279) 유실 상태 노출**: 재구성 전이라도 클라이언트가 "집계가 준비되지 않은 상태"를 판별할 수 있도록 RANK-001·RANK-002 응답에 `status`(`READY`|`REBUILDING`)를 추가한다. 기존 필드는 그대로 두는 필드 추가라 하위 호환이다. 유실 상태에서도 오류가 아니라 200이다.
- **(2026-08-10 확정, 이슈 #288) 장애 상태 노출**: 유실(ZSET이 비어 있음)과 장애(Redis 연결 자체가 안 됨)는 다르다. `status`에 `UNAVAILABLE`을 추가해, Redis 연결 장애로 조회 자체가 실패한 경우도 500 대신 200 + `status: UNAVAILABLE`로 응답한다. `RankingStore`의 읽기 경로(`topN`·`findAllAtScore`·`countStrictlyGreater`·`score`)가 `RankingStoreUnavailableException`을 던지고 `RankingService`가 이를 상태값으로 변환한다 — 쓰기 경로(랭킹 갱신)는 기존과 동일하게 실패를 삼킨다(의도한 비대칭). 랭킹과 무관하지만 같은 이슈에서 함께 고친 항목으로, `BithumbFeedLifecycle.startFeed`도 시세 클라이언트 시작 실패를 삼켜 Redis가 죽어 있어도 애플리케이션 기동 자체는 성공한다(시세 기능만 저하).

#### RANK-002 내 랭킹 조회 (2차 MVP)

- `GET /api/rankings/me?market=`로 인증 사용자 본인의 실현손익 순위를 조회한다.
- `market`은 RANK-001과 동일하게 필수다.
- 상위 `limit`건 안에 들지 않아도 순위를 반환한다 — ZSET 순위 조회 자체는 순위표 내 노출 여부와 무관하게 계산되므로 "순위 없음"으로 대체하지 않는다. 다만 RANK-001의 공동 순위 규칙이 적용된 **보정된** 순위를 반환해야 한다 — RANK-001과 동일한 애플리케이션 계층 보정(`countStrictlyGreater` 기반) 로직을 재사용한다.
- RANK-001과 동일하게 매도 체결 이력이 없는 사용자는 랭킹 대상이 아니다. **(2026-08-05 확정) RANK-001이 이런 회원을 ZSET에 member 자체가 없는 상태로 처리하는 것과 같은 원칙으로, 전용 상태값(enum)이나 오류 코드를 새로 만들지 않고 순위 필드를 부재(null)로 표현한다.** 이슈 #279에서 추가한 `status`(`READY`|`REBUILDING`)는 이 확정을 뒤집는 것이 아니라 다른 축이다 — "매도 이력 없음"은 여전히 `rank: null` + `status: READY`이고, `status`는 그 `null`이 "집계 유실 중"(`REBUILDING`)인 경우와 구별되게 해줄 뿐이다.
- **(2026-08-05 확정) 응답의 닉네임은 RANK-001과 동일하게 마스킹 없이 그대로 노출한다** — `RankingListItemResponse.nickname`이 이미 그렇게 구현돼 있으므로(PR #196) RANK-002도 같은 관례를 따른다.
- 다른 사용자의 순위는 이 엔드포인트로 조회할 수 없다.

**Decision Gate**: 전체 오류 코드, Redis 키 이름·자료구조 세부는 착수 시 `docs/specs/014-ranking`에서 확정한다. 닉네임 노출 범위·매도 이력 없는 사용자 응답 형태·다중 인스턴스 score 갱신 경합·공동 순위 계산 방식은 위와 같이 확정했으므로 이 Decision Gate에서 제외한다(2026-08-05). **재구성 배치 등 보상·정합성 확인 경로도 이슈 #279에서 확정해 제외한다(2026-08-09)** — 트리거·절차·`status` 노출은 위 RANK-001 항목에 적었다.

### 커뮤니티

#### COM-001 게시물

- 로그인 사용자는 텍스트 제목과 본문으로 게시물을 작성한다.
- 목록·단건조회·작성·본인 수정·본인 삭제를 제공한다.
- 목록은 최신순 페이지네이션을 제공한다.
- 수익 인증 분류, 거래 첨부, 좋아요, 신고는 1차에서 제외한다.

#### COM-002 댓글

- 로그인 사용자는 게시물에 평면형 댓글을 작성한다.
- 게시물 상세에서 댓글 목록을 오래된 순으로 조회한다.
- 본인 댓글만 삭제할 수 있다.
- 댓글 수정과 대댓글은 1차에서 제외한다.

#### COM-003 권한

- 게시물·댓글 조회는 인증 사용자에게 허용한다.
- 수정·삭제는 소유자만 가능하다.
- 다른 사용자의 콘텐츠 변경 시 403 `FORBIDDEN`을 반환한다.

---

## 5. API 계약 (1차 MVP 기준 목록)

Base URL: `/api` (버전 프리픽스 없음 — 2026-07-23 확정, `docs/conventions.md`). 아래 목록은 클라이언트가 호출하는 **실제 외부 경로를 그대로** 적는다 — 문서마다 `/auth/...`와 `/api/auth/...`가 섞이지 않게 한다.

> **이 목록은 1차 MVP 시점의 경로 집합이며 현재 제공 중인 전체 경로가 아니다.** 2차에서 추가·구현된 경로(`GET /api/rankings`, `GET /api/instruments/{id}/price-moves`, `GET /api/instruments/{id}/news`, `GET /api/market/briefing`, `POST·PATCH /api/trades/{id}/journal`, `POST·PATCH /api/trades/{id}/sell-journal`, `GET·POST·DELETE /api/favorites`, `POST /api/education/practice/intentions`, `GET /api/education/practice/synthetic-prices/{id}`)는 여기 반영하지 않는다. **실제 라우트 전수는 `docs/api-routes.md`, 요청·응답·오류 계약은 `docs/api-contracts.md`가 정본이다** — 이 두 문서는 controller 변경과 같은 커밋에서 갱신된다(CLAUDE.md 규칙 7). 아래 목록은 1차 완료 범위를 확인하는 용도로만 유지한다.

### 인증

- `POST /api/auth/email-verifications` (인증번호 발송)
- `POST /api/auth/email-verifications/confirm` (인증번호 확인 → `signupVerificationToken` 발급)
- `POST /api/auth/password-resets` (비밀번호 재설정 인증번호 발송 — 공개 경로, AUTH-006)
- `POST /api/auth/password-resets/confirm` (인증번호 + 새 비밀번호를 한 요청으로 받아 재설정 적용, 새 토큰 미발급 — 공개 경로, AUTH-006)
- `POST /api/auth/signup`
- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `POST /api/auth/logout`
- `GET /api/auth/me`
- `PATCH /api/auth/me/nickname`
- `PATCH /api/auth/me/password` (현재 비밀번호 확인 기반 비밀번호 변경 — AUTH-005)
- `POST /api/auth/email-changes` (새 이메일 인증번호 발송)
- `POST /api/auth/email-changes/confirm` (인증번호 확인과 이메일 변경)
- `GET /api/auth/oauth/{provider}/authorize?purpose=login|reauth`
- `GET /api/auth/oauth/{provider}/callback` (`state`에 묶인 `purpose`에 따라 로그인 또는 재인증 처리)

### 계좌·포트폴리오

- `GET /api/accounts/summary?market=`
- `GET /api/holdings?market=`
- `GET /api/portfolio`
- `GET /api/trades?market=&cursor=&limit=` (`market` 필수 — 생략 시 통합 조회는 지원하지 않는다)

`POST /api/trades/{buyTradeId}/journal`(매수 투자일기 작성)은 2차(1차 고도화) 범위로 이동했다 (2026-07-28 Notion 확인). 1차 API 계약에 포함하지 않는다.

### 종목·시세

- `GET /api/instruments?market=`
- `GET /api/instruments/{instrumentId}`
- `GET /api/instruments/{instrumentId}/price`
- `GET /api/instruments/{instrumentId}/candles?interval=1m&from=&to=` (주식은 `stock_candles` 재생 분봉, 코인은 빗썸 공개 캔들 API 실시간 조회·진행 중 분봉 포함 — MKT-008. 코인의 실시간 화면 표출은 이 엔드포인트의 재조회로 충당하며 전용 스트림을 두지 않는다)[^crypto-sse-card-only]
- `GET /api/stocks/stream` (SSE, 주식 시세)

[^crypto-sse-card-only]: **(2026-08-10, 이슈 #286으로 카드 알림 한정 대체 — 구현 완료)** "코인은 전용 스트림을 두지 않는다"는 이 결정은 시세(가격) 표출 목적에서는 그대로 유지된다 — 캔들 재조회로 충분하다는 원문 판단은 바뀌지 않았다. 다만 **카드 확정 알림**(가격이 아니라 "변동 카드가 방금 확정됐다"는 사건 자체)은 재조회로 알 수 없어 `028-crypto-card-sse-push`에서 `GET /api/cryptos/stream`을 신설했다 — MKT-010이 MKT-008을 부분 대체한 것과 같은 패턴으로, 원문 문장은 그대로 두고 이 각주로만 대체 사실을 남긴다. 세부는 §3 "구현 현황"의 "코인 변동 카드 확정 SSE push" 행과 ADR-0018을 따른다.

`interval`에 일봉·주봉·월봉(`1d`·`1w`·`1M`)을 추가하는 것(MKT-009)은 2차(1차 고도화) 범위로 이동했다. 1차 API 계약은 `interval=1m`만 포함한다.

### 주문

- `GET /api/orders?market=&cursor=&limit=` (`market` 필수 — 생략 시 통합 조회는 지원하지 않는다. `cursor`·`limit`은 `GET /api/trades`와 동일한 페이지네이션 방식 — 2026-08-04 확정, 이슈 #177. 실제 구현은 이슈 #182(`docs/specs/018-order-list-pagination`)에서 완료했다)
  - 인증 사용자의 주문을 최신 요청순으로 시장별로 반환한다.
  - 응답 항목: `orderId`, `market`, `instrumentId`, `side`, `orderType`, `status`, `quantity`, `requestedAt` (`docs/api-contracts.md` 정본 기준 — `symbol` 없음, `quantity`)
  - 체결가격·체결금액·수수료·실현손익·체결시각은 포함하지 않고 `GET /api/trades`에서 조회한다.
- `POST /api/orders`
- 요청 예시: `{"market":"STOCK","instrumentId":1,"side":"BUY","orderType":"MARKET","quantity":"10"}`
- Header: `Idempotency-Key: <UUID>`
- 성공: 즉시 `FILLED` 주문과 체결 반환
- 검증 실패: 주문·체결·잔고 변경을 남기지 않음

### 게시판

- `GET /api/community/posts?page=&size=`
- `POST /api/community/posts`
- `GET /api/community/posts/{postId}`
- `PATCH /api/community/posts/{postId}`
- `DELETE /api/community/posts/{postId}`
- `GET /api/community/posts/{postId}/comments`
- `POST /api/community/posts/{postId}/comments`
- `DELETE /api/community/comments/{commentId}`

### 공통 오류

| HTTP | code | 의미 |
|---|---|---|
| 400 | VALIDATION_ERROR | 형식·수량·최소금액 오류 |
| 400 | EMAIL_VERIFICATION_FAILED | 인증번호 불일치·만료·미발급 |
| 400 | OAUTH_EMAIL_REQUIRED | 제공자가 이메일을 제공하지 않음 |
| 400 | OAUTH_AUTHORIZATION_FAILED | OAuth 인가 코드·state 검증 실패 |
| 401 | UNAUTHORIZED | 인증 없음·만료 |
| 403 | FORBIDDEN | 소유권·권한 없음 |
| 403 | REAUTHENTICATION_FAILED | 현재 비밀번호 또는 연결된 OAuth 제공자 재인증 실패 |
| 404 | NOT_FOUND | 대상 없음 |
| 404 | FAVORITE_NOT_FOUND | 즐겨찾기 없음 (타인 소유도 존재를 숨겨 404 — 2차) |
| 405 | METHOD_NOT_ALLOWED | 라우트가 지원하지 않는 HTTP 메서드로 요청 (Issue #267) |
| 409 | DUPLICATE_RESOURCE | 이메일·닉네임·소셜계정 중복, 인증 요청 시 기존 회원 |
| 409 | INSTRUMENT_NOT_TRADABLE | 거래 불가 종목의 즐겨찾기 등록 (2차) |
| 409 | PRACTICE_STEP_LOCKED | 투자 실습 선행 단계 미충족 — favorite 부재·종목 불일치 (2차) |
| 409 | PRACTICE_ALREADY_COMPLETED | 투자 실습을 이미 완료함 (2차) |
| 409 | EMAIL_VERIFICATION_REQUIRED | 가입 토큰 없음·만료·사용됨·이메일 불일치 |
| 409 | ACCOUNT_LINK_REQUIRED | 같은 이메일의 일반 회원 존재 — 소셜 자동 연결 불가 |
| 409 | SOCIAL_ACCOUNT_ONLY | 비밀번호가 없는 소셜 로그인 전용 계정 — 비밀번호 재설정 불가 (AUTH-006) |
| 409 | INSUFFICIENT_CASH | 매수 가능 현금 부족 |
| 409 | INSUFFICIENT_QTY | 매도 가능 수량 부족 |
| 409 | MARKET_CLOSED | 주식 장 종료 |
| 409 | PRICE_UNAVAILABLE | 최신 시세 없음 |
| 409 | IDEMPOTENCY_CONFLICT | 같은 키의 다른 요청 |
| 422 | UNSUPPORTED_ORDER_TYPE | `POST /api/orders`(시장가 전용)에 `orderType="LIMIT"` 요청. **2차에 지정가가 추가돼도 이 거부는 유지된다** — 지정가는 `POST /api/orders/limit` 별도 경로다 (LMT-001) |
| 429 | TOO_MANY_REQUESTS | 인증번호 발송·입력 시도 제한 초과 |
| 500 | INTERNAL_ERROR | 서버 내부 오류 |
| 502 | OAUTH_PROVIDER_ERROR | OAuth 공급자 장애·타임아웃·비정상 응답 |
| 502 | MARKET_DATA_PROVIDER_ERROR | 외부 시세 공급자 조회 실패 (빗썸 캔들 REST 장애·타임아웃·파싱 불가 — MKT-008) |

위 표는 **2026-08-04 기준 `com.finplay.api.common.ErrorCode` enum 전체(26개)와 1:1로 일치한다.** 코드가 정본이며, 새 오류 코드를 추가하면 이 표도 같은 커밋에서 갱신한다. 엔드포인트별로 어떤 코드가 나오는지는 `docs/api-contracts.md`가 정본이다.

오류 형식:

```json
{"error":{"code":"INSUFFICIENT_CASH","message":"현금 잔고가 부족합니다.","requestId":"..."}}
```

---

## 6. 데이터 모델

### 1차 MVP 핵심 테이블

- `users`: 이메일, 비밀번호 해시, 닉네임, 역할, 상태
- `social_accounts`: 제공자, 제공자 회원ID, 연결 회원
- `refresh_tokens`: 토큰 해시, 만료시각, 폐기시각, 회원
- `email_verifications`: 이메일, 인증번호 해시, 시도횟수, 만료시각, 최근발송시각, 확인시각, 가입토큰 해시, 토큰 만료시각, 토큰 소비시각, 생성시각 (가입 전 단계 — 회원 행과 무관)
- `reauth_tokens`: 회원, 인증 방식, OAuth 제공자, 재인증 토큰 해시, 만료시각, 소비시각, 생성시각 (V5 — 이전 서술의 `reauthentications`는 실제 테이블명이 아니었다, 2026-08-04 정정)
- `email_change_verifications`: 회원, 기존 이메일, 새 이메일, 인증번호 해시, 시도횟수, 만료시각, 최근발송시각, 확인시각, 생성시각
- `password_reset_verifications`: 이메일, 인증번호 해시, 시도횟수, 만료시각, 최근발송시각, 소비시각, 생성시각 (비로그인 단계 — `user_id` 외래키를 두지 않는다. 미가입 이메일 요청도 발송 제한 집계용 행을 남기기 때문이며, 인증번호 해시·만료시각·최근발송시각이 모두 비어 있는 행이 발송하지 않고 거부된 요청이다)
- `accounts`: 회원·시장별 현금, 시드머니, 실현손익
- `instruments`: 시장, 심볼, 이름, 호가단위, 최소주문금액, 거래가능
- `stock_candles`: 종목, 거래일, 분봉시각, 시가·고가·저가·종가·거래량, 데이터출처, 수집시각 (최근 20영업일만 보관, `UNIQUE(instrument_id, trading_date, candle_time)`)
- `stock_replay_sessions`: 서비스 날짜, 원본 거래일, 준비상태(`preparation_status`: PREPARING·READY·FAILED — OPEN·CLOSED는 저장하지 않고 Clock으로 계산), 결과 결정시각(`resolved_at`), 실패사유 (`UNIQUE(service_date)`)
- `market_data_imports`: 데이터출처, 원본 거래일, 수집시각, 상태(SUCCESS·PARTIAL_SUCCESS·FAILED·SKIPPED_DUPLICATE), 실패사유 (V11. **"파일 중복식별값" 컬럼은 실제로 만들지 않았다** — MKT-005의 동일 거래일 재수집 판정을 후속 이슈로 미루면서 함께 빠졌고, 기본 멱등성은 `UNIQUE(instrument_id, trading_date, candle_time)`이 담당한다, 2026-08-04 정정)
- `orders`: 멱등키, 계좌, 종목, 구분, 유형, 수량, 체결상태
- `trades`: 불변 체결 원장, 가격, 수량, 금액, 수수료, 실현손익, nullable `stock_replay_session_id` FK (주식 fill은 당시 current session, 코인은 null — 2차 tutorial-only OCO 선행 변경)
- `holdings`: 계좌·종목별 현재 보유수량과 평균단가
- `holding_lots`: 매수 체결별 최초수량·잔여수량·원가·매수수수료
- `trade_allocations`: 매도 체결이 소비한 매수 lot과 배분수량·원가
- `community_posts`: 작성자, 제목, 본문, 생성·수정시각
- `post_comments`: 게시물, 작성자, 본문, 생성시각

### 핵심 제약

- `UNIQUE(users.email)`
- `UNIQUE(users.nickname)`
- `UNIQUE(social_accounts.provider, provider_user_id)`
- `UNIQUE(email_verifications.token_hash)` + `INDEX(email_verifications.email, created_at)` (발송 제한 기간 집계용)
- `UNIQUE(reauth_tokens.token_hash)`
- `INDEX(email_change_verifications.user_id, new_email, created_at)` (유효 요청과 발송 제한 조회용)
- `INDEX(password_reset_verifications.email, created_at)` (발송 제한 기간 집계용 — 거부된 요청 행도 함께 센다)
- `UNIQUE(accounts.user_id, accounts.market)`
- `UNIQUE(instruments.symbol)`
- `UNIQUE(orders.user_id, orders.idempotency_key)`
- `UNIQUE(holdings.account_id, holdings.instrument_id)`
- `UNIQUE(stock_candles.instrument_id, stock_candles.trading_date, stock_candles.candle_time)`
- `UNIQUE(stock_replay_sessions.service_date)`
- `UNIQUE(trades.order_id)`, `UNIQUE(holding_lots.buy_trade_id)` (1차 주문은 즉시 전량 체결이라 주문 1건에 체결 1건, 매수 체결 1건에 lot 1건)
- 체결·lot·배분은 외래키로 주문·계좌·종목·회원 소유권을 추적한다.

### 2차 MVP에서 추가된 테이블 (2026-08-04 기준 실제 마이그레이션)

Flyway 마이그레이션은 V1~V28까지 적용돼 있다. 아래는 2차에서 추가·변경된 것만 적는다 — 정본은 `src/main/resources/db/migration/`이며 스키마 변경은 항상 새 번호 마이그레이션으로만 한다(ADR-0004).

**AI 피드백 (V13, `docs/specs/012-ai-feedback`)**

- `market_news_items`: 종목, 제목, 언론사, 원문 URL, 발행시각 — **본문은 저장하지 않는다**(C-004). `UNIQUE(instrument_id, url)` (한 기사가 두 종목에 붙을 수 있어 URL 단독 unique가 아니다)
- `price_move_events`: 종목, 원본 거래일, 이벤트 유형, 변동 구간, 서술 — `UNIQUE(instrument_id, origin_trade_date, event_type, window_start)`
- `price_move_event_sources`: 변동 이벤트 ↔ 기사 N:M — `UNIQUE(price_move_event_id, market_news_item_id)`
- `instrument_news_summaries`: 종목·거래일·요약 범위(`PRE_MARKET`·`FULL`·`ROLLING_24H`) — `UNIQUE(instrument_id, origin_trade_date, scope)`
- `market_briefings`: 시장·원본 거래일 개장 전 브리핑 — `UNIQUE(market, origin_trade_date)`
- `price_move_peer_stats`: 카드별 집단 행동 집계 (**회원 식별자 없이 집계만**) — `UNIQUE(price_move_event_id, service_date)`
- `trade_feedbacks`: 매도 직후 AI 서술 — `UNIQUE(trade_id)`. 조회 엔드포인트(FEED-007, `GET /api/ai/post-sell/{tradeId}`)가 최초 조회 시 서술을 생성해 이 테이블에 저장하고 이후 재사용한다 (Issue #208)

**투자일기 (V15·V17·V18·V21, `docs/specs/007-journal`)**

- `buy_trade_journals`: 매수 체결별 회고 1건 + `updated_at`(V21) — `UNIQUE(buy_trade_id)` (JOUR-001·002)
- `sell_trade_journals`: 매도 체결별 회고 1건 + `updated_at`(V18) — `UNIQUE(sell_trade_id)` (JOUR-003·004)

**투자 실습 (V16·V19·V27·V28, `docs/specs/016-investment-education-policy`·`026-market-order-practice-tutorial` + ADR-0012)**

- `practice_progresses`: 회원·튜토리얼별 진행 상태 — `UNIQUE(user_id, tutorial_key)`. **완료 여부는 영구 기록이라 DB에 유지한다**
- ~~`favorites`(V14)~~·~~`practice_intentions`(V16)~~ → **V19에서 DROP.** ADR-0012에 따라 서버 힙 메모리(`ConcurrentHashMap`) 저장으로 전환했다. 재시작·다중 인스턴스 시 유실을 감수하며 API 계약은 바뀌지 않는다. `favoriteId`·`intentionId`는 프로세스 기동마다 1부터 재채번된다
- `practice_market_observations`·`practice_market_reflections`(V27): `026` 경로의 3단계 가격 관찰(append-only)과 자유 복기. 복기는 `UNIQUE(user_id, tutorial_key)` — `016`의 `UNIQUE(user_id, exit_plan_id)` 설계와 다르다(exit plan이 없는 경로)
- `practice_completions`(V28): 튜토리얼 완료의 불변 기록. `026` 경로에서 최초 생성됐으며 완료 후 evidence가 바뀌어도 회귀하지 않는다

**주문 원장 (V20)**

- `trades.stock_replay_session_id`: nullable FK 추가 — 주식 체결은 당시 current replay session, 코인은 null. **STOCK ⇒ non-null 불변식은 신규 행에만 성립한다**(기존 행은 백필하지 않았다). 2차 tutorial-only OCO의 선행 변경 (PR #191)

### Redis 키 책임

- **코인 실시간 시세와 랭킹 순위 두 가지가 대상이다.** 주식 1분봉은 MySQL(`stock_candles`)이 정본이다.
- 코인 시세(`price:crypto:<symbol>` Hash, `feed:crypto:status` String): 최신 시세, 시세 수신시각, 연결상태만 저장한다.
- 코인 가격 스냅샷(`price:crypto:<symbol>:snapshots` ZSET, FEED-005·006·PR #236): 코인 변동 감시가 쓰는 시계열이다. `CryptoPriceSnapshotService`가 **매 분 1회 최신가를 샘플링**해 적재하고 retention(σ lookback)을 넘은 원소를 제거한다 — 개별 틱을 모두 담는 것이 아니고 OHLCV도 아니다. **정본은 아니다** — 유실되면 변동 감시가 그만큼의 과거 구간을 못 볼 뿐이다.
- **(2026-08-06 MKT-010, 구현 완료)** 코인 진행 중·확정 1분봉 캐시: `candle:crypto:<symbol>:1m:<epochMinute>`(Hash — open·high·low·close·volumeScaled) + `candle:crypto:<symbol>:1m:since`(String — 이 심볼의 캐시가 연속적으로 신뢰 가능한 시작 분). TTL 4시간(응답 상한 200봉에서 역산). MySQL에는 저장하지 않는다(새 테이블 없음). 위 `:snapshots`와는 **별개 키**다 — 그쪽은 분당 샘플, 이쪽은 체결 단위 OHLCV 누적으로 목적이 다르다.
- 랭킹(`ranking:<market>` ZSET, RANK-001·2026-08-04 추가): 시장별 실현손익 순위. **정본은 MySQL `accounts.realized_pnl`이고 ZSET은 조회 성능용 파생 데이터다** — 유실 시 MySQL 원장으로 재구성한다. 매도 체결 커밋 이후(after-commit)에만 갱신한다.
  - **(2026-08-09 이슈 #279) 재구성 상세**: 트리거는 기동 완료 시점 1회 + 매일 04:20(KST) 배치이며 항상 전체 재구성이다. 대상 판정은 **`trades.side = 'SELL'` 이력 유무**(손익 값이 아니다)이고 score는 `accounts.realized_pnl`이다. 교체는 임시 키 `ranking:<market>:rebuild`에 전량 적재한 뒤 `RENAME`으로 원자 교체해, 재구성 중 조회가 빈 값·부분 값을 보지 않게 한다(대상이 0건이면 임시 키가 만들어지지 않으므로 `RENAME` 대신 본 키를 `DEL`한다). 재구성은 읽기 전용이라 원장을 변경하지 않고, 실패해도 예외를 밖으로 던지지 않는다 — 호출자가 기동 훅과 스케줄러라 예외가 새면 기동이 실패하거나 스케줄러 스레드가 죽는다. 재구성 전 상태는 두 랭킹 응답의 `status`(`READY`|`REBUILDING`)로 노출한다.
  - **(2026-08-10 이슈 #288) 연결 장애와 유실의 구분**: 위 재구성은 "ZSET을 읽을 수 있는데 유실됐다"는 전제였다. Redis 연결 자체가 안 되는 경우는 `RankingStore`의 읽기 경로(`topN`·`findAllAtScore`·`countStrictlyGreater`·`score`)가 예외를 던지고 `RankingService`가 이를 `status: UNAVAILABLE`로 변환한다 — 500이 아니라 200이다. 쓰기 경로(after-commit 랭킹 갱신)는 기존과 동일하게 재시도 후 실패를 삼킨다. 코인 시세 키(`feed:crypto:status` 등)를 다루는 `BithumbFeedLifecycle.startFeed`도 같은 이슈에서 시작 실패를 삼키도록 고쳤다 — Redis 장애로 이 훅이 예외를 던지면 애플리케이션 기동 자체가 실패해(`ApplicationReadyEvent` 동기 리스너) 랭킹·시세와 무관한 API까지 전부 죽는 문제였다.
- 회원·잔고·체결·보유·게시물 원장은 Redis에 저장하지 않는다.
- Redis 유실 시 MySQL 원장은 보존되며, 새 시세 수신 전 주문은 차단한다.

---

## 7. 기술 계획

### 기술 스택

> **2026-08-04 기준 `build.gradle` 실측값이다.** 이전 목록은 실제 의존성과 어긋나 있었다 — `OAuth2 Client`를 쓴다고 적었으나 실제로는 추가하지 않았고(OAuth는 `RestClient`로 직접 호출한다), Flyway·springdoc·jjwt·Spring AI·정적 분석 도구가 빠져 있었다.

- Java 17 (toolchain)
- Spring Boot 4.1.0
- Gradle 9.5.1 (wrapper)
- Spring Web MVC, Validation, Security, Data JPA, Data Redis, WebSocket, Actuator
- **`spring-boot-starter-oauth2-client`는 쓰지 않는다** — 카카오·네이버는 공통 어댑터에서 `RestClient`로 직접 호출한다 (AUTH-003)
- 인증 토큰: jjwt 0.13.0 (api·impl·jackson), `spring-security-crypto`
- Flyway (`spring-boot-starter-flyway` + `flyway-mysql`), `ddl-auto=validate` — 스키마 변경은 마이그레이션으로만 (ADR-0004)
- QueryDSL 5.1.0 (jakarta) — 목록 조회
- springdoc-openapi 3.0.0 (Swagger UI 라이브 문서)
- MySQL (mysql-connector-j), Redis
- **Spring AI `spring-ai-starter-model-openai` 2.0.0** — AI 피드백 서술 생성 (ADR-0011). 기본 프로바이더는 OpenAI이고 1.x는 Boot 3.x 전용이라 쓸 수 없다. 서비스 로직은 `NarrativeGenerator`만 알고 프로바이더를 모른다
- Docker Compose (`spring-boot-docker-compose` developmentOnly)
- Lombok
- 품질 게이트: Spotless(NAVER eclipse 설정), SpotBugs 6.5.9(main만), JaCoCo(라인 커버리지 40% 게이트). `./gradlew build`에 포함된다
- Kafka는 1차에서 쓰지 않는다. 2026-08-04 현재도 의존성·컨테이너 모두 없다. 2차 동시성 제어 도입 시점에 함께 추가한다 (이슈 #123 — 준비용 컨테이너를 미리 두면 로컬 기동만 느려지고 실익이 없다)
- 이메일 발송은 Resend HTTP API를 `RestClient`로 호출한다 (별도 의존성 추가 없음). 운영 프로필에서만 활성화한다
- 주식 실시간 시세는 한국투자 KIS Open API 국내주식 WebSocket을 사용한다 (`PRIVATE` 환경 기본, `PUBLIC`은 C-007 조건 충족 시에만). **1차·2차 모두 실시간 구현체는 만들지 않았다** (MKT-007)
- JUnit 5, Testcontainers (MySQL만 — Redis 컨테이너는 쓰지 않는다)

### 구조

기능별 모듈형 모놀리스로 구성한다. 패키지는 `com.finplay.api.<도메인>` (ADR-0002 도메인 패키지 구조와 동일).

1차 MVP 도메인:

- `auth`: 회원·이메일 인증·JWT·OAuth·재인증·내 정보 조회·수정·Refresh Token
- `account`: 두 계좌 생성·잔고·요약
- `market`: 종목·주식 시세 공급자(`StockPriceProvider` — KIS 과거 데이터 재생·KIS 실시간)·빗썸·Redis 시세·캔들
- `order`: 시장가 검증·체결·멱등성·수수료
- `portfolio`: 보유·FIFO lot·거래내역·평가손익·합산 요약
- `community`: 게시물·댓글·소유권
- `common`: 오류 응답, 시간, 인증 사용자. 범용 Manager 금지

2차 MVP에서 추가된 도메인 (2026-08-04 기준 실제 패키지):

- `feedback`: 뉴스·공시 수집, 변동 원인 카드, 종목 뉴스 요약, 개장 전 브리핑, LLM 서술 생성(`NarrativeGenerator`)·후검증·템플릿 폴백
- `journal`: 매수·매도 회고 작성·수정
- `ranking`: 실현손익 랭킹 (Redis ZSET 저장소 + 체결 커밋 이후 이벤트 리스너)
- `favorite`: 즐겨찾기 (ADR-0012에 따라 DB가 아닌 인메모리 저장)
- `education`: 투자 실습 — legacy 사전 의도(인메모리)·영속 attempt/run과 자동 위험 snapshot(DB)·진행/불변 완료(DB)·canonical 29+1 차트·legacy 합성 시세(`education.synthetic`)

**도메인 간 호출은 service를 경유하고 다른 도메인의 repository를 직접 주입하지 않는다** (`docs/conventions.md`). 투자 실습 OCO는 `education application → order application port` 한 방향만 허용한다.

### 트랜잭션 경계

- 회원가입: 가입 토큰 소비 + 회원 + 두 계좌 생성 원자 처리. 닉네임 중복 등으로 실패하면 토큰 소비도 함께 롤백되어 남은 유효시간 안에 재시도할 수 있다
- 시장가 매수: 주문+체결+현금차감+holding+lot 생성 원자 처리
- 시장가 매도: 주문+체결+FIFO 배분+holding+현금증가+실현손익 원자 처리
- OAuth 신규 가입: 회원+두 계좌 생성 원자 처리 (기존 회원과의 자동 병합 없음)
- 닉네임 변경: 재인증 검증+중복 확인+회원 닉네임 변경 원자 처리
- 이메일 변경 확인: 인증번호 소비+회원 이메일 변경+기존 Refresh Token 전체 폐기 원자 처리
- 1차는 단일 애플리케이션 인스턴스의 DB 트랜잭션 정합성을 보장한다.
- 비관락·분산락·다중 인스턴스 경합 검증은 2차 범위다. **분산락은 2026-08-04 현재도 도입하지 않았다.**
- **2차 추가 (ADR-0012, legacy 경로)**: 즐겨찾기·사전 의도는 DB 행이 아니라 서버 힙 메모리에 있어 `SELECT ... FOR UPDATE`를 걸 수 없다. 그 직렬화는 **사용자 단위 in-process 잠금**(`ReentrantLock`)으로 대체하며, 호출부는 이미 DB 트랜잭션·행 잠금을 잡은 상태에서만 이 락을 빌린다(`progress(DB) → favorite 락(in-memory)` 순서). in-process 잠금은 JVM 안에서만 유효해 다중 인스턴스 경계를 넘지 못한다. `039` 현재 흐름의 attempt/run·위험 snapshot은 이 감수 범위에 속하지 않고 MySQL에 영속된다.
- **2차 추가 (RANK-001)**: Redis는 MySQL 트랜잭션에 참여하지 않으므로 랭킹 ZSET 갱신은 **체결 커밋 이후(after-commit)** 에만 수행한다. 커밋 전에 갱신하면 롤백 시 Redis에만 반영이 남는다.
- **2차 추가 (ADR-0012 감수 위험)**: `@Transactional` 롤백이 인메모리 쓰기를 되돌리지 않는다 — DB 트랜잭션 안에서 메모리에 저장한 뒤 커밋이 실패하면 메모리 쪽만 남을 수 있다. 튜토리얼 상태에 한정된 감수 사항이며 매매 원장에는 적용되지 않는다.

### Docker 로컬 환경

- MySQL과 Redis는 1차 애플리케이션 필수 서비스다. Compose에는 이 둘만 둔다.
- Kafka는 Compose에 포함하지 않는다. 2차에서 실제로 도입할 때 컨테이너를 추가한다 (이슈 #123).
- 로컬 설정과 비밀값은 환경변수로 주입한다.
- OAuth 실키가 없으면 Fake OAuth 프로필로 실행할 수 있어야 한다.
- 이메일 발송 키(`RESEND_API_KEY`·`EMAIL_FROM`)가 없어도 애플리케이션과 자동 테스트가 정상 기동해야 한다 — 로컬·테스트 프로필은 Fake 발송기를 사용하며 이 값들을 필수로 요구하지 않는다.
- KIS 키(1차 MVP에서는 과거 분봉 수집 배치가 쓰는 `KIS_APP_KEY`·`KIS_APP_SECRET`)가 없어도 애플리케이션과 자동 테스트는 정상 기동해야 한다 (MKT-007). 자동 테스트는 Fake 응답 클라이언트를 쓴다.

---

## 8. 구현 태스크 순서 (1차 MVP)

> **이 절은 1차 MVP의 태스크 분해이며 전부 완료됐다.** 2차 MVP는 태스크 번호가 아니라 spec 폴더 단위로 진행한다 — `012-ai-feedback`, `013-candle-interval`, `014-ranking`, `016-investment-education-policy`, `018-order-list-pagination`, `019-exit-price-policy`. 2차 spec 번호는 1차 태스크 번호(1~10)와 대응하지 않는다.

각 태스크는 실패 테스트 작성, 최소 구현, 대상 테스트, 전체 테스트, 문서 정합성 확인 순서로 완료한다. 태스크 1개당 `docs/specs/NNN-*/` spec 폴더 1개를 만들어 `/feature`로 진행한다.

번호는 spec 폴더 대응용이며 착수 순서와 항상 같지는 않다 — 실제 진행 순서는 마일스톤을 따른다. 특히 **10 배포는 9 통합 검증보다 먼저 착수한다** (1주차에 첫 배포, 9는 마지막 주 졸업시험).

1. 프로젝트 기반과 Docker 환경
   - Spring Boot·Gradle·MySQL·Redis·QueryDSL·Testcontainers 설정
   - 프로필과 환경변수, 공통 오류, 주입 가능한 Clock
2. 인증과 계좌
   - 이메일 가입·JWT·Refresh Token
   - 가입 트랜잭션과 두 계좌 생성
   - OAuth 어댑터와 Fake 제공자
   - 내 정보 조회와 현재 비밀번호·OAuth 재인증 기반 닉네임·이메일 변경
3. 종목과 시세
   - 16+12 종목 시드
   - 주식 KIS Open API 데이터 수집·검증·1분봉 재생
   - 빗썸 WebSocket·Redis 최신 시세·장애 상태
4. 시장가 매수
   - 검증·수수료·멱등성
   - 주문·체결·보유·매수 lot 원자 처리
5. 시장가 매도
   - FIFO lot 소비와 allocation
   - 수수료·현금·실현손익·보유 갱신
6. 조회
   - 계좌 요약·보유자산·평가손익·주문 목록·거래내역
   - QueryDSL 목록 조회
7. ~~매수 투자일기~~ — 2026-07-28 Notion 확인 결과 1차 MVP가 아니라 2차(1차 고도화) 범위로 이동. (번호 결번은 유지 — 아래 태스크는 8·9·10 그대로) **2차에서 `007-journal` spec으로 착수해 JOUR-001 매수 작성(PR #181)·JOUR-003 매도 작성(PR #189)·JOUR-004 매도 수정(PR #192)·JOUR-002 매수 수정(PR #201)까지 구현 완료했다.**
8. 커뮤니티
   - 게시물 CRUD·페이지네이션
   - 평면 댓글 작성·조회·본인 삭제
9. 통합 검증
   - 핵심 E2E
   - API·DB·PRD 요구사항 ID 교차 확인
   - Docker 실행과 헬스체크
10. 배포와 CI (착수는 9보다 앞선다)
    - 최소 CI — PR에서 전체 build·테스트 실행
    - 운영 환경 배포 (첫 단계는 수동 배포)
    - 배포 후 스모크 스크립트 실행

---

## 9. 테스트 계획과 완료 기준

### 필수 자동 검증

- `./gradlew clean test`
- MySQL Testcontainers 통합 테스트
- Redis Testcontainers 통합 테스트
- Docker Compose MySQL·Redis 헬스체크
- Fake 발송기 기반 이메일 인증 테스트 — 만료·시도 5회 초과 무효화·재발송 시 이전 코드 무효화·가입 토큰 1회 소비·가입 실패 시 토큰 미소비
- Fake OAuth 신규 가입 테스트와 같은 이메일의 기존 회원이 있을 때 409 거부 테스트
- 일반 로그인에서 계좌·시드머니가 추가 생성되거나 초기화되지 않는 테스트
- 내 정보 조회의 민감정보 미노출, 현재 비밀번호 검증, OAuth 동일 제공자 재인증·다른 계정 거부·재인증 토큰 일회 소비 테스트
- 닉네임 중복·이메일 변경 인증 만료·5회 실패·재발송 무효화·Refresh Token 전체 폐기·계좌와 거래 원장 불변 테스트
- 주식 KIS Open API 데이터 수집·재생 테스트 — 고정 Clock으로 평일 08:10 수집·08:40 재생세션 확정(READY/FAILED·폴백)·09:00 이후 개장·장중·마감 시나리오
- 주식 시세 공급자 테스트 — `KisHistoricalReplayPriceProvider`가 `StockPriceProvider`로 주입되어 `PriceQueryService`·캔들 조회·SSE가 정상 동작, KIS 키 없이 빌드·기동 성공, 화면 가격과 체결가격이 같은 Provider 사용
- **(1차 MVP 범위 아님 — KIS 실시간 후속)** 공급자 선택·fail-fast 테스트 — 설정 조합별 Provider 선택, 승인 없는 PUBLIC+KIS_REALTIME 기동 실패. 1차 MVP에는 선택 설정 자체가 없다
- **(1차 MVP 범위 아님 — KIS 실시간 후속)** 두 Provider가 `PriceQueryService` 동일 계약을 만족하는 테스트 — 1차 MVP에는 구현체가 하나뿐이라 비교 대상이 없다
- **(1차 MVP 범위 아님 — KIS 실시간 후속)** `FakeKisRealtimePriceProvider` 기반 KIS 연결 끊김·재연결 테스트와 실시간 틱의 1분 OHLCV 집계 테스트
- 빗썸 Feed Fake의 정상·끊김·재연결 테스트
- Idempotency-Key 중복 체결 방지 테스트
- FIFO 다중 매수·부분 매도·다중 lot 소비 테스트
- 주문 목록과 체결내역의 필드·의미 분리 테스트
- 주식·코인 합산 포트폴리오의 빈 계좌·단일 시장·양 시장 손익 계산 테스트
- 게시물·댓글 타인 변경 거부 테스트

### 핵심 E2E

1. 이메일 인증을 완료해 받은 가입 토큰으로 가입하거나, Fake OAuth로 가입한다.
2. 주식·코인 계좌가 각 1,000만원으로 생성된다.
3. 일반 로그인에서 두 계좌와 잔액이 그대로 유지되는지 확인한다.
4. 현재 비밀번호 또는 Fake OAuth 재인증 후 닉네임과 이메일을 변경하고, 계좌·거래 데이터가 유지되는지 확인한다.
5. 주식 2회 매수 후 일부 매도한다.
6. FIFO 원가·수수료·현금·보유수량·실현손익과 주식·코인 합산 포트폴리오를 확인한다.
7. 주문 목록에서 주문요청 정보가 보이고, 체결내역에서 체결가격·수수료·실현손익이 보이는지 확인한다.
8. 코인 최신 시세에서 매수·매도한다.
9. 코인 시세 연결을 끊고 주문 차단을 확인한다.
10. 게시물을 작성·수정하고 댓글을 작성·삭제한다.
11. 다른 계정으로 내 정보·주문·체결·콘텐츠를 조회하거나 변경할 수 없는지 확인한다.

### 별도 외부 스모크 테스트

- 실제 빗썸 WebSocket 연결과 최신 틱 수신
- **(MKT-010, 이슈 #242, 완료)** 실제 빗썸 WebSocket 메시지 포맷 확인 — `ticker`·`transaction` 두 채널 모두 실제 연결로 필드 구성을 확인했다(이슈 #104 Decision Gate 해소). `transaction` 채널에서 분봉 거래량을 채울 체결 수량(`contQty`)을 받을 수 있음을 확인했다
- 실제 카카오 OAuth
- 실제 네이버 OAuth
- 실제 Resend 발송과 이메일 수신 (인증된 발신 도메인 필요)
- 실제 KIS Open API WebSocket 연결과 국내주식 체결 틱 수신 (실키 필요 — 자동 테스트 통과와 구분해 보고)

외부 키·네트워크가 없는 경우 미실행으로 보고하며 자동 테스트 통과와 구분한다.

### 완료 정의

- 1차 요구사항 ID마다 대응 코드·테스트가 있다.
- 필수 자동 검증이 모두 통과한다.
- Docker 로컬 환경에서 핵심 E2E를 재현할 수 있다.
- 2차·3차 기능이 1차 코드 경로에 섞이지 않는다.
- 실행한 검증과 미실행 외부 검증, 남은 위험이 보고서에 구분돼 있다.

---

## 10. 의존성·위험·후속 단계

- 실제 OAuth 검증에는 카카오·네이버 Client ID/Secret과 Callback URL이 필요하다.
- 실제 빗썸 검증에는 외부 네트워크가 필요하다. 코인 차트(MKT-008)는 실시간 시세(WebSocket)와 별개로 **빗썸 공개 캔들 REST API에도 런타임 의존**한다 — 이 API가 응답하지 않으면 코인 차트만 오류가 되고 현재가·주문은 영향을 받지 않는다.
- **Decision Gate (빗썸 공개 API 레이트리밋)**: 코인 차트는 요청마다 빗썸을 조회하고 응답을 캐시하지 않는다. 동시 사용자 증가로 실제 레이트리밋 차단이 관측되면 그때 짧은 TTL 캐시 도입 여부를 판단한다 — 관측 전에 임의의 캐시 계층이나 TTL 숫자를 넣지 않는다.
  - **(2026-08-06) MKT-010이 이 게이트에 걸렸고, 폐기하지 않고 충족시켰다(구현 완료).** MKT-010은 캐시 계층을 새로 넣는 작업이므로 이 게이트의 대상이었다. 근거는 레이트리밋 차단 관측만이 아니라 "외부 호출량이 사용자 수에 비례한다"는 구조적 문제와 "빗썸 장애가 곧 차트 장애"라는 가용성 문제까지 포함한다. **실측 결과**: `BithumbWebSocketFeedClient`·`CryptoCandleStore`·`CachedCryptoCandleProvider`를 실제 빗썸 연결로 직접 조립해 측정 — 요청 30건을 캐시가 이미 덮은 구간(최근 수집분)에 보낸 결과 캐싱 없음은 30건 모두 빗썸 호출(항상 1:1), 캐싱 적용은 0건(**절감률 100%**, BTC·XRP 둘 다). 이 100%는 요청 구간이 `since`(그 심볼의 캐시 신뢰 시작점) 이후로 완전히 들어있을 때의 수치이며, 서버 재시작 직후나 그보다 과거를 포함하는 요청은 그 구간만큼 여전히 빗썸을 호출한다. TTL은 이 실측이 아니라 응답 상한 200봉에서 역산했다(성능 실측으로 정할 값이 아니라 계약에서 유도되는 값이기 때문).
- 주식 과거 데이터는 한국투자증권(KIS) Open API로 이용한다 (C-006) — 한국투자증권 문의 결과 공공데이터로 확인되어 제3자 표출에 별도 서면 허가가 필요하지 않다. 수집은 KIS Open API 호출로 이루어지며, 재생·주문 로직은 API 응답 형식이 확정되기 전에도 샘플 데이터로 먼저 설계·검증한다.
- **Decision Gate (한국투자 서면 답변, 실시간 한정)**: 공개 환경(`PUBLIC`)의 시세 공급자를 `KIS_REALTIME`으로 전환하는 것은 한국투자증권의 서면 허용 또는 계약 완료가 확인된 뒤에만 판단한다. 답변 전까지 공개 배포는 `KIS_HISTORICAL`로 진행하며, 아직 받지 않은 답변 내용을 문서·코드·설정 기본값에 반영하지 않는다 (C-007). 과거 데이터는 이미 공공데이터로 확인되어 이 게이트 대상이 아니다. **개인 개발 환경의 KIS 실시간 구현은 1차 MVP에서 진행하지 않고 후속(KIS 실시간 틱 집계)으로 미룬다** — 이 게이트(공개 전환)와는 별개의 범위 결정이며, 게이트 자체는 그대로 유효하다.
- **Decision Gate (KIS 과거 데이터 응답 세부사항)**: 수집 엔드포인트와 배치 실행시각은 조사·확인으로 **확정됐다**(MKT-005 참조). 다음 항목만 실제 응답을 확인하기 전까지 확정하지 않는다 — 분봉 응답의 개별 필드명, 분봉 timestamp가 구간 시작·종료 중 무엇을 의미하는지, 거래 없는 분을 상품이 어떻게 표현하는지, 정상 분봉 개수 판단 기준(임시 숫자 포함). 응답 파싱을 격리된 한 지점에 두어 외부 스모크 후 그 지점만 교정한다. 재생기·주문 로직·수집 골격(전체 응답 오류 검증)은 샘플 데이터로 먼저 설계·테스트할 수 있다.
- Java 17과 Spring Boot 4.1.0은 호환되며 현재 Gradle wrapper는 9.5.1이다.
- 2차 시작 전 동시성 모델, 지정가 체결 트리거·큐 소비 방식(LMT-002 Decision Gate), AI 피드백 접점, 랭킹·알림 계약을 별도 Spec으로 확정한다. AI 피드백 접점은 `docs/specs/012-ai-feedback`로 확정했다 (2026-08-02). **랭킹은 `014-ranking`으로 확정·구현까지 완료했다 (RANK-001, PR #196).** 동시성 모델·지정가·알림은 2026-08-04 현재 미확정·미착수다.
- 지정가는 배치가 아니라 상시 처리(이벤트 드리븐)로 재확정했으며 (2026-08-03), 큐 구현 세부사항은 `docs/specs/015-limit-order`에서 확정한다.
- 랭킹은 시장별(STOCK/CRYPTO) 분리·Redis ZSET 메커니즘과 RANK-001(전체 랭킹)·RANK-002(내 랭킹) 정책(공동 순위, limit 기본 10·상한 50, 매도 체결 이력 없는 회원 제외)을 확정했으며 (2026-08-03, 이슈 #139), SSE push는 검토 후 REST 조회로 대체했다 — 체결마다 push할 만큼 긴급한 데이터가 아니고 Notion API 표에도 REST 엔드포인트만 등재돼 있다.
- 남은 응답 필드·오류 코드·Redis 키 설계(RANK-001 Decision Gate)는 `docs/specs/014-ranking`에서 확정했고 **`GET /api/rankings`까지 구현 완료했다 (PR #196)** — Redis 키는 `ranking:<market>` ZSET이고 공동 순위는 애플리케이션 계층에서 보정한다. **RANK-002 내 랭킹(`GET /api/rankings/me`)도 구현 완료했다 (PR #234) — RANK-001과 동일한 ZSET·보정 공식을 재사용한다.**
- 알림은 지정가 매수·매도 체결 알림으로 범위를 한정하고 SSE 실시간 push를 포함하는 것으로 확정했으며(2026-08-04, NOTI-001~005, 이슈 #140), SSE 연결·인증 스코프·알림 페이로드 필드 등 세부 계약은 착수 시 알림 spec에서 확정한다 — **spec 폴더는 미생성이고 번호 미배정이다**(`013`은 캔들 기간 확장이 점유). 알림은 지정가 체결 트리거(LMT-002)가 선행되어야 하므로 LMT보다 먼저 착수하지 않는다. **(2026-08-07) 팀 일정 재조정으로 알림 착수 시점을 2차 MVP(1차 고도화) → 3차 MVP(2차 고도화)로 미뤘다(이슈 #261)** — 위 범위·전달 방식 확정 내용은 그대로 유지된다.
- 주문 목록(`GET /api/orders`)은 `market` 필수·`cursor`·`limit` 페이지네이션 도입으로 확정했다(2026-08-04, PORT-003, 이슈 #177) — 실제 구현은 이슈 #182에서 완료했다.
- **뉴스 출처·저작권 결정을 3차에서 2차로 앞당겼다 (2026-08-02)**: 2차 "AI 피드백"이 뉴스를 근거로 쓰게 되면서 3차를 기다릴 수 없게 됐다. 출처는 네이버 뉴스 검색 API(분 단위 발행시각)와 OpenDART 공시검색 API(일 단위 접수일자)로 확정했고, 저작권 대응은 "본문 미저장, 제목·언론사·원문 URL·발행시각만 저장"이다 (C-004). **갱신주기도 함께 확정했다 (2026-08-03, 2026-08-04 정정)** — 기사가 나오는 당일에 상시 수집한다(~~주식 평일 08:00~16:00 30분 간격, 코인 2시간 간격~~ → **주식·코인 공통 24시간 30분 간격**). 네이버 API가 날짜 범위 지정을 지원하지 않고 `display` 상한이 100이라, 재생 시점에 소급 수집하면 대형주의 앞부분이 잘린다. **장중으로 한정하면 안 된다** — 개장 전 브리핑과 전장 요약의 근거 구간이 "직전 거래일 15:30 ~ 당일 09:00"(약 17.5시간)인데 장중만 돌리면 이 구간이 통째로 비어 브리핑이 매일 빈 값이 된다. 상세는 `docs/specs/012-ai-feedback` FEED-001.
- 투자 실습의 OCO 없는 holding 기반 완료·불변 completion 원칙은 `docs/specs/026-market-order-practice-tutorial`이 소유하고, **샘플 종목의 현재 사용자 흐름 정본은 `docs/specs/039-tutorial-flow-redesign`이다**(Backend PR #381 / companion frontend PR #30). `039`은 사용자·시장별 영속 attempt/run, BUY 진입가 기준 자동 -3%/+5% 위험 snapshot, 29개 과거 일봉+가상 현재 일봉 하나, 명시적 3초 tick, current-run 원자 재시작, 완료 read-only replay를 주식·코인 공통으로 제공한다. 기존 즐겨찾기(PR #165·#171·#173)·사전 의도(PR #176)·표시 전용 합성 시세(PR #195)는 ADR-0012의 인메모리/legacy 호환 계약으로 유지되지만 현재 프론트 완료 흐름의 전제가 아니다. OCO 기반 설계는 `016`·`019`·`020`·`021`이 3차 정본이며 별도 URL·완료 key를 사용한다. 8개 투자 지식 과정·배지·RAG 교육 코치는 3차 착수 승인 뒤 별도 구현 spec으로 분리한다. AI 리포트 주기는 3차 시작 전 별도 Spec으로 확정한다. 종목 뉴스 요약은 2차로 이동했으므로 3차에서는 다루지 않는다. ~~뉴스 갱신주기~~는 2026-08-03에 확정했다(위 항목).
