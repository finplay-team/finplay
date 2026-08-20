# API 계약 — account

account 도메인의 API 계약 상세다. 전체 라우트를 한눈에 보는 지도는 `docs/api-routes.md`에 있다.

**controller를 추가/변경하면 `docs/api-routes.md`의 라우트 목록과 이 문서를 같은 커밋에서 함께 갱신한다** (CLAUDE.md 규칙, reviewer 리뷰 모드 점검 항목).

블랙박스 QA는 구현 코드(`src/main`)를 읽지 않고 이 문서와 spec만을 계약 근거로 사용한다 (`docs/context-router.md`).

---

### 시장별 계좌 요약 조회

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/accounts/summary?market= | Access Bearer 필수 | `market`(필수, `STOCK`\|`CRYPTO` 리터럴만 허용) | 200 `{"cashBalance":9300000,"reservedCash":500000,"holdingsValue":720000,"totalValue":10020000,"realizedPnl":0,"unrealizedPnl":20000}` (`AccountSummaryResponse`, 6개 필드 고정) | `market` 누락 또는 `STOCK`\|`CRYPTO` 외 리터럴(예: `FOREX`)은 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 006 ACCT-002, Issue #81, 015 LMT-004(`reservedCash`), Issue #235 |

조회 대상은 요청에서 받지 않고 Access Token의 인증 사용자 본인 소유의 해당 시장 계좌로만 결정한다(경로·쿼리에 계좌 식별자 없음 — 타인 계좌 조회 자체가 불가능한 구조). 보유 종목이 없어도(신규 가입 직후 등) 예외 없이 200과 0으로 채운 응답을 반환한다(단 `cashBalance`는 초기 시드머니).

응답 6개 필드: `cashBalance`(현금잔고, 계좌 원장 값 그대로) · `reservedCash`(코인 지정가 매수로 예약된 현금, `accounts.reserved_cash` 원장 값 그대로 — 015 LMT-004, 이슈 #235로 추가) · `holdingsValue`(활성 보유의 평가금액 합산 — 아래 시세 무효 처리 참고) · `totalValue`(`cashBalance + holdingsValue`) · `realizedPnl`(계좌 원장 값 그대로, 재계산 없음) · `unrealizedPnl`(활성 보유의 미실현손익 합산 — 아래 시세 무효 처리 참고). `returnRate`(시드머니 대비 총손익 비율)는 화면에서 쓰지 않게 되면서 응답에서도 제거했다 — 종목별 수익률(`docs/api/portfolio.md`의 `returnRate`, 투자원가 기준)과 분모가 달라 나란히 보여줄 때 혼동을 준다는 판단이었다. `reservedCash`는 원장 값을 그대로 노출할 뿐이며 `totalValue` 등 다른 필드의 계산식을 바꾸지 않는다 — "주문 가능 금액"을 뜻하는 `availableCash` 같은 파생 필드는 추가하지 않는다(클라이언트가 `cashBalance - reservedCash`로 직접 계산할 수 있다, spec.md `docs/specs/015-limit-order/spec.md` "확정된 설계 결정" 11번).

**시세 무효(`PriceStatus.UNAVAILABLE`) 보유 처리 (PR #96 리뷰 반영, 2026-07-30 정책 확정 — 038로 발동 범위 축소, 이슈 #384)**: 주식 시세는 재생(replay) 기반이라, 이 처리가 도입될 당시에는 장 마감 시간대(평일 09:01 이전·주말·공휴일)엔 전 종목이 동시에 `UNAVAILABLE`이 됐다. **038 이후로는 더 이상 그렇지 않다** — `marketStatus=CLOSED`여도 오늘보다 이전 날짜의 마지막 `READY` 재생세션이 있으면 그 세션의 마지막 상태로 `AVAILABLE`이 유지되므로(`docs/api/market.md`의 "종목 현재가 조회" 절 참조), 이 대체 경로는 더 이상 주말·공휴일마다 상시 발동하지 않는다. 이제 이 경로가 실제로 쓰이는 경우는 **폴백 후보 자체가 없을 때**(예: 폴백 대상 거래일에도 분봉이 하나도 없거나, 아직 어떤 재생세션도 완료된 적 없는 극초기 상태)로 좁혀졌다 — 그때 이 종목은 여전히 `UNAVAILABLE`이고 아래 대체 처리가 적용된다. 이 상태의 보유 종목을 합산에서 완전히 제외(원가까지 제외)하면 실제로는 손실이 없는데도 `holdingsValue=0`·수익률 대폭 마이너스로 보이는 오류가 발생한다(QA 재현: 00:52 KST, 현금+보유 10주 계좌가 수익률 -7%로 응답). 그래서 시세 무효 보유는 다음과 같이 처리한다 — **`evaluationAmount` 대신 `costBasis`(보유수량 × 평균단가, 시세와 무관하게 항상 채워짐)를 `holdingsValue`에 합산하고, `unrealizedPnl` 합계에는 0만 가산한다**(손익을 알 수 없으니 "원금만큼 있다"로 취급, 손익 자체는 표시하지 않음). 시세 유효(`AVAILABLE`) 보유는 기존과 동일하게 `evaluationAmount`·`unrealizedPnl`을 그대로 합산한다 — 038 이후에는 주말에도 폴백값으로 이 경로를 타므로 보유 종목이 있는 계좌는 주말에도 실제 평가금액·수익률을 본다. 한 종목의 시세 무효가 전체 계좌 요약 조회를 막지는 않는다(예외 없이 200).
