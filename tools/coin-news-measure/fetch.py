# 코인 뉴스 질의어·필터 측정(이슈 #179)의 1단계 — 네이버 검색 응답을 받아 원문 그대로 덤프한다.
"""
수집과 분석을 나눈 이유가 있다.

한 번 받아두면 필터 후보(현행·후보1·후보2·합본)를 **네이버를 다시 부르지 않고** 몇 번이든 비교할 수 있다.
후보마다 새로 받으면 그 사이 기사 목록이 바뀌어 무엇 때문에 숫자가 달라졌는지 구분되지 않는다.

**제목을 여기서 손질하지 않는다.** 태그 제거·엔티티 해제·길이 절단은 운영 코드(NaverNewsCollector.cleanTitle)가
하는 일이고, 2단계 분석기가 그 메서드를 그대로 부른다. 여기서 미리 씻으면 두 벌이 되어 조용히 갈라진다.

**측정은 필터만 본다 — 운영은 필터 앞에서 기사를 더 버린다.** URL 추출 실패·빈 제목·pubDate 파싱 실패로
저장 전에 떨어지는 기사가 있으므로 방향은 항상 "측정 통과 수 >= 실제 저장 수"다. 그 폐기를 확인할 수 있도록
title 외에 link(네이버 링크)와 pubDate도 함께 덤프한다 — 2026-08-07 스냅샷에서는 영향 0으로 확인됐지만,
pubDate는 애초에 덤프에 없어 확인 자체가 불가능했다.

사용법 — 보고서의 3행 표를 얻으려면 **세 번 받아야 한다** (질의 방식 하나에 덤프 하나다)
    python tools/coin-news-measure/fetch.py --suffix ""      # 이름만
    python tools/coin-news-measure/fetch.py                  # 개정 전 질의어(이름 + " 코인")
    python tools/coin-news-measure/fetch.py --symbol-suffix  # 채택 질의어 — 이름 + " " + 심볼
    python tools/coin-news-measure/fetch.py --only 트론,에이다   # 부분 덤프. 파일명에 -partial이 붙는다

분석(2단계)은 **반드시 --rerun-tasks를 붙인다.**
    .\\gradlew.bat test --tests CoinNewsFilterMeasurementTest --rerun-tasks
덤프가 test task의 입력이 아니라, 붙이지 않으면 Gradle이 UP-TO-DATE로 건너뛴다. 그래도 BUILD SUCCESSFUL이
찍히므로 **이전 스냅샷 보고서를 새 결과로 읽게 된다** — 이 도구를 남긴 이유를 무력화하는 조용한 실패다.

키는 프로젝트 루트 .env의 NAVER_SEARCH_CLIENT_ID / NAVER_SEARCH_CLIENT_SECRET을 읽는다.
결과는 tools/coin-news-measure/out/raw-<label>.json (gitignore 대상 — 클론 직후엔 out/이 비어 있어
2단계 테스트가 그냥 건너뛴다). 자세한 함정은 같은 폴더의 README.md에 있다.
"""

import argparse
import datetime
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

# 윈도우 콘솔 기본이 cp949라 한글·em dash가 그대로 나가면 UnicodeEncodeError로 죽는다 (실측 2026-08-07).
# 파일 쓰기는 encoding="utf-8"로 이미 안전하고, 여기서 고치는 것은 표준출력뿐이다.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# V7 시드의 코인 12종 (src/main/resources/db/migration의 instruments INSERT와 같은 목록).
# 여기 하드코딩한 이유는 측정 스크립트가 DB 없이 돌아야 하기 때문이다. 시드가 바뀌면 이 목록도 갱신한다.
COINS = [
    ("BTC", "비트코인"),
    ("ETH", "이더리움"),
    ("XRP", "리플"),
    ("SOL", "솔라나"),
    ("DOGE", "도지코인"),
    ("ADA", "에이다"),
    ("TRX", "트론"),
    ("AVAX", "아발란체"),
    ("LINK", "체인링크"),
    ("DOT", "폴카닷"),
    ("BCH", "비트코인캐시"),
    ("ETC", "이더리움클래식"),
]

# spec §외부 API 호출 상세 — NaverNewsCollector와 같은 값을 쓴다. 다르면 측정이 운영과 다른 응답을 본다.
BASE_URL = "https://naverapihub.apigw.ntruss.com/search/v1/news"
DISPLAY = 100
SORT = "date"
HEADER_ID = "X-NCP-APIGW-API-KEY-ID"
HEADER_SECRET = "X-NCP-APIGW-API-KEY"

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT_DIR = pathlib.Path(__file__).resolve().parent / "out"


def load_env_keys():
    """루트 .env에서 키 두 개를 읽는다. 환경변수가 이미 있으면 그쪽을 우선한다."""
    client_id = os.environ.get("NAVER_SEARCH_CLIENT_ID")
    client_secret = os.environ.get("NAVER_SEARCH_CLIENT_SECRET")
    env_path = ROOT / ".env"
    if (not client_id or not client_secret) and env_path.exists():
        for line in env_path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            value = value.strip().strip('"').strip("'")
            if key.strip() == "NAVER_SEARCH_CLIENT_ID" and not client_id:
                client_id = value
            elif key.strip() == "NAVER_SEARCH_CLIENT_SECRET" and not client_secret:
                client_secret = value
    if not client_id or not client_secret:
        sys.exit(
            "NAVER_SEARCH_CLIENT_ID / NAVER_SEARCH_CLIENT_SECRET을 찾지 못했다.\n"
            f"환경변수로 주거나 {env_path}에 넣어라."
        )
    return client_id, client_secret


def fetch_one(query, client_id, client_secret):
    """기사 100건을 받아 items를 그대로 돌려준다. 실패는 감추지 않고 그대로 올린다."""
    params = urllib.parse.urlencode({"query": query, "display": DISPLAY, "sort": SORT})
    request = urllib.request.Request(f"{BASE_URL}?{params}")
    request.add_header(HEADER_ID, client_id)
    request.add_header(HEADER_SECRET, client_secret)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.loads(response.read().decode("utf-8")).get("items", [])
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")[:400]
        sys.exit(f"[{query}] HTTP {error.code}\n{body}")
    except urllib.error.URLError as error:
        sys.exit(f"[{query}] 연결 실패 — {error.reason}")


def build_query(name, symbol, args):
    if args.symbol_suffix:
        return f"{name} {symbol}"
    return f"{name}{args.suffix}"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--suffix", default=" 코인", help='질의어 접미. 현행은 " 코인"')
    parser.add_argument(
        "--symbol-suffix",
        action="store_true",
        help="후보 3 — 접미 대신 심볼을 붙인다(트론 TRX)",
    )
    parser.add_argument("--only", default="", help="쉼표로 구분한 종목명. 비우면 12종 전부")
    parser.add_argument("--label", default="", help="출력 파일 이름표. 비우면 질의 방식에서 만든다")
    args = parser.parse_args()

    only = {name.strip() for name in args.only.split(",") if name.strip()}
    targets = [(s, n) for s, n in COINS if not only or n in only]
    if only and len(targets) != len(only):
        missing = only - {n for _, n in targets}
        sys.exit(f"시드에 없는 종목명 — {', '.join(sorted(missing))}")

    # 파일 이름표는 ASCII로 둔다 — 한글을 넣으면 윈도우에서 파일명이 깨진다 (실측 2026-08-07).
    if args.label:
        label = args.label
    elif args.symbol_suffix:
        label = "symbol"
    elif args.suffix.strip():
        label = "coin-suffix"  # 현행 질의어(이름 + " 코인")
    else:
        label = "bare"
    # 부분 덤프는 전체 덤프를 같은 파일명으로 덮어쓰면 안 된다 — 분석기가 보는 종목 목록이 줄어들면
    # 다른 종목명 판정(O)과 접두 보호가 둘 다 달라지는데 보고서에는 아무 표시가 남지 않는다.
    if only and not args.label:
        label += "-partial"
    client_id, client_secret = load_env_keys()

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    records = []
    for index, (symbol, name) in enumerate(targets, start=1):
        query = build_query(name, symbol, args)
        items = fetch_one(query, client_id, client_secret)
        # 판정은 title만 쓰지만 나머지 셋도 남긴다 — originallink는 "이 기사가 정말 무관한가"를 사람이
        # 확인할 때, link·pubDate는 운영이 필터 **앞에서** 버리는 기사(URL 추출 실패·pubDate 파싱 실패)를
        # 확인할 때 쓴다. 측정이 그 폐기를 모델링하지 않으므로 방향은 항상 "측정 >= 실제 저장"이다.
        records.append(
            {
                "symbol": symbol,
                "name": name,
                "query": query,
                "received": len(items),
                # 원문 그대로다. 손질은 2단계가 운영 코드로 한다.
                "items": [
                    {
                        "title": i.get("title", ""),
                        "url": i.get("originallink", ""),
                        "link": i.get("link", ""),
                        "pubDate": i.get("pubDate", ""),
                    }
                    for i in items
                ],
            }
        )
        print(f"  {index:2d}/{len(targets)}  {query:<20} {len(items):3d}건")
        if index < len(targets):
            time.sleep(0.2)  # 레이트리밋 여유. 12건이라 총 2.4초다

    out_path = OUT_DIR / f"raw-{label}.json"
    # 종목 수·수집 시각·부분 여부를 덤프에 박아 둔다 — 보고서만 보고는 "12종목 전부인가"를 알 수 없고,
    # 부분 덤프가 섞이면 O 판정과 접두 보호가 조용히 달라진다.
    out_path.write_text(
        json.dumps(
            {
                "label": label,
                "collectedAt": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
                "instrumentCount": len(records),
                "expectedInstrumentCount": len(COINS),
                "partial": len(records) != len(COINS),
                "records": records,
            },
            ensure_ascii=False,
            indent=1,
        ),
        encoding="utf-8",
    )
    total = sum(r["received"] for r in records)
    print(f"\n{len(records)}/{len(COINS)}종목 {total}건 → {out_path}")
    if len(records) != len(COINS):
        print("  ! 부분 덤프다 — 종목 목록이 줄면 다른 종목명 판정(O)과 접두 보호가 함께 달라진다")
    # --rerun-tasks가 없으면 Gradle이 UP-TO-DATE로 건너뛰고도 BUILD SUCCESSFUL을 찍어,
    # 이전 스냅샷 보고서를 새 결과로 읽게 된다 (덤프는 test task의 입력이 아니다).
    print("다음 — .\\gradlew.bat test --tests CoinNewsFilterMeasurementTest --rerun-tasks")


if __name__ == "__main__":
    main()
