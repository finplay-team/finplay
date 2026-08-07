# coin-news-measure — 코인 뉴스 질의어·제목 필터 측정 (이슈 #179)

2단계로 나뉜다. `fetch.py`가 네이버 응답을 **원문 그대로** `out/raw-<label>.json`에 덤프하고,
`CoinNewsFilterMeasurementTest`(외부 호출 없음)가 그 덤프에 필터 후보를 적용해 `out/report.md`를 만든다.
나눈 이유는 후보마다 새로 받으면 그 사이 기사 목록이 바뀌어 **무엇 때문에 숫자가 달라졌는지 구분되지
않기 때문**이다.

측정 결과와 그 해석의 정본은 `docs/specs/012-ai-feedback/run-log.md`의 2026-08-07 절이다.
이 문서는 **도구를 다시 돌릴 사람이 걸려 넘어지는 자리 세 곳**만 적는다.

## 1. 분석은 `--rerun-tasks` 없이는 조용히 건너뛴다

```
.\gradlew.bat test --tests CoinNewsFilterMeasurementTest --rerun-tasks
```

덤프 디렉터리(`out/`)는 test task의 **입력이 아니다.** 덤프를 새로 받아도 Gradle은 test task를
`UP-TO-DATE`로 판정해 건너뛰고, 그래도 `BUILD SUCCESSFUL`을 찍는다. 그 결과 **이전 스냅샷으로 만든
`out/report.md`를 새 결과로 읽게 된다** — 이 도구를 남긴 유일한 이유를 무력화하는 조용한 실패다.

`out/`을 test task의 입력으로 물리는 방법도 있으나 택하지 않았다. `tasks.withType(Test)`는 전역이라
**gitignore된 디렉터리가 전체 테스트의 up-to-date 판정에 끼어들고**, 덤프를 새로 받을 때마다 무관한
1,900여 건이 통째로 재실행된다. 안내 문구 한 줄이 그보다 싸다.

## 2. 클론 직후에는 테스트가 그냥 건너뛴다

`out/`은 gitignore 대상이다(`.gitignore`의 `tools/coin-news-measure/out/`). 덤프가 없으면 테스트는
`Assumptions`로 스스로를 건너뛰므로 **실패가 아니라 무음**이다. 먼저 `fetch.py`로 덤프를 받아야 한다.

```
python tools/coin-news-measure/fetch.py --symbol-suffix
```

키는 프로젝트 루트 `.env`의 `NAVER_SEARCH_CLIENT_ID` / `NAVER_SEARCH_CLIENT_SECRET`을 읽는다.

## 3. 보고서의 3행 표를 얻으려면 `fetch.py`를 세 번 돌려야 한다

질의 방식 하나가 덤프 하나다. 분석기는 `out/`에 있는 `raw-*.json`을 전부 읽어 **있는 만큼만** 행을
만들므로, 한 번만 받으면 표가 1행짜리로 나오는데 그 자체는 오류로 보이지 않는다.

```
python tools/coin-news-measure/fetch.py --suffix ""      # raw-bare.json         (이름만)
python tools/coin-news-measure/fetch.py                  # raw-coin-suffix.json  (개정 전, 이름 + " 코인")
python tools/coin-news-measure/fetch.py --symbol-suffix  # raw-symbol.json       (채택, 이름 + 심볼)
```

`--only`로 종목을 좁히면 파일명에 `-partial`이 붙어 전체 덤프를 덮어쓰지 않는다. 부분 덤프는 종목
목록이 줄어 **다른 종목명 판정(`O`)과 접두 보호가 둘 다 달라지므로** 전체 덤프와 같은 표에 섞어 읽으면
안 된다. 덤프 최상위에 `collectedAt`·`instrumentCount`·`partial`이 기록된다.

## 측정의 경계

- **측정은 필터만 본다.** 운영은 필터 **앞에서** URL 추출 실패·빈 제목·`pubDate` 파싱 실패로 기사를 더
  버리므로 방향은 항상 **측정 통과 수 ≥ 실제 저장 수**다. 확인용으로 `link`·`pubDate`도 덤프한다.
- **제목 손질은 다시 구현하지 않는다.** 태그 제거·엔티티 해제·절단은 운영 코드
  `NaverNewsCollector.cleanTitle`을 그대로 부른다(그래서 그 메서드가 패키지 전용이다).
- **채택 규칙이 곧 지표 `S`라, 새 규칙이 만드는 누락은 이 측정이 구조적으로 볼 수 없다.** 알려진 사례는
  `이더리움클래식`의 띄어쓰기(`"이더리움 클래식(ETC)"`)다 — run-log의 2026-08-07 절에 있다.
