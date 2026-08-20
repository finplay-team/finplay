# API 계약 — portfolio

portfolio 도메인의 API 계약 상세다. 전체 라우트를 한눈에 보는 지도는 `docs/api-routes.md`에 있다.

**controller를 추가/변경하면 `docs/api-routes.md`의 라우트 목록과 이 문서를 같은 커밋에서 함께 갱신한다** (CLAUDE.md 규칙, reviewer 리뷰 모드 점검 항목).

블랙박스 QA는 구현 코드(`src/main`)를 읽지 않고 이 문서와 spec만을 계약 근거로 사용한다 (`docs/context-router.md`).

---

### 시장별 보유 종목 목록 조회

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/holdings?market= | Access Bearer 필수 | `market`(필수, `STOCK`\|`CRYPTO` 리터럴만 허용) | 200 `[{"holdingId":101,"instrumentId":1,"symbol":"005930","name":"삼성전자","quantity":10,"reservedQuantity":0,"averagePrice":70000,"currentPrice":71000,"evaluationAmount":710000,"unrealizedPnl":10000,"returnRate":0.0143,"priceStatus":"AVAILABLE"},{"holdingId":102,"instrumentId":2,"symbol":"000660","name":"SK하이닉스","quantity":5,"reservedQuantity":2,"averagePrice":120000,"currentPrice":null,"evaluationAmount":null,"unrealizedPnl":null,"returnRate":null,"priceStatus":"UNAVAILABLE"}]` (`HoldingListItemResponse[]`, 12개 필드 고정); 보유 종목이 없으면 200 `[]` | `market` 누락 또는 `STOCK`\|`CRYPTO` 외 리터럴(예: `FOREX`)은 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 006 PORT-001, Issue #52, 015 LMT-004(`reservedQuantity`), Issue #235, Issue #444(`holdingId`) |

조회 대상은 요청에서 받지 않고 Access Token의 인증 사용자 본인 소유의 해당 시장 계좌가 보유한 **활성**(`isActive=true`) 종목으로만 결정한다(경로·쿼리에 계좌·보유 식별자 없음 — 타인 보유 조회 자체가 불가능한 구조). 전량 매도한 종목은 목록에서 제외되며, 보유 종목이 없으면 예외 없이 200과 빈 배열을 반환한다. `market` 필드는 응답에 포함하지 않는다 — 요청 쿼리로 이미 단일 시장으로 필터링되어 있다.

응답 12개 필드: `holdingId`(holding row의 실제 PK — `POST /api/exit-plans`의 `holdingId` 입력이 바로 이 값이다, Issue #444) · `instrumentId`·`symbol`·`name`(종목 표시 정보, 화면이 추가 조회를 하지 않도록) · `quantity`·`reservedQuantity`(코인 지정가 매도로 예약된 수량, `holdings.reserved_quantity` 원장 값 그대로 — 015 LMT-004, 이슈 #235로 추가) · `averagePrice`(원장 값, 시세와 무관하게 항상 채워짐) · `currentPrice`·`evaluationAmount`·`unrealizedPnl`·`returnRate`(아래 시세 무효 처리 참고) · `priceStatus`(`"AVAILABLE"`\|`"UNAVAILABLE"`). `reservedQuantity`는 원장 값을 그대로 노출할 뿐이며 `quantity`·평가금액 등 다른 필드의 계산식을 바꾸지 않는다 — "주문 가능 수량"을 뜻하는 `availableQuantity` 같은 파생 필드는 추가하지 않는다(클라이언트가 `quantity - reservedQuantity`로 직접 계산할 수 있다, `docs/specs/015-limit-order/spec.md` "확정된 설계 결정" 11번).

**시세 무효(`PriceStatus.UNAVAILABLE`) 종목 처리 — 계좌 요약(`docs/api/account.md`)과 다른 정책**: 이 API는 종목 단위 목록이므로 account 문서의 "시세 무효 시 원가로 폴백" 집계 정책을 적용하지 않는다. 대신 `currentPrice`·`evaluationAmount`·`unrealizedPnl`·`returnRate` 4개 필드를 **`null` 그대로 노출**하고 `priceStatus="UNAVAILABLE"`로 구분한다. 이유: 종목 단위에서 원가·0손익으로 채우면 "이 종목은 손익이 정확히 0원"이라는 구체적이고 틀린 사실을 특정 종목에 대해 단정하게 되므로, 집계값 왜곡(계좌 요약)보다 더 나쁜 오정보가 된다. `quantity`·`averagePrice`는 원장 값이라 시세 무효 여부와 무관하게 항상 값이 채워진다. 한 종목의 시세 무효가 전체 목록 조회를 막지는 않는다(예외 없이 200).

정렬 기준: 응답 배열은 종목 심볼(`symbol`) 오름차순으로 고정된다(`HoldingRepository.findAllByAccountIdAndIsActiveTrue`의 `ORDER BY h.instrument.symbol`, PR #97 리뷰 권장사항 1).
