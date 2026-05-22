# Lie Hint 설계안

## 1. 개요

Lie Hint는 출제자가 정답 단어와 여러 개의 힌트를 등록하되, 그중 하나를 거짓 힌트로 지정하는 게임이다.

플레이어는 힌트를 보고 정답을 추측한다. 정답을 맞히면 이어서 "어떤 힌트가 거짓이었는지"를 고른다. 최종 성공은 정답 단어와 거짓 힌트를 모두 맞힌 경우로 본다.

기존 WordPlay의 핵심 흐름인 "게임 생성 -> 공유 URL -> 닉네임 입력 -> 플레이 -> 리더보드"를 그대로 사용한다.

## 2. 게임 규칙

### 2.1 출제

출제자는 아래 값을 입력한다.

- 게임 제목
- 정답 단어
- 힌트 3개
- 거짓 힌트 번호 1개
- 출제자 닉네임
- 공개 여부

초기 버전에서는 힌트를 3개로 고정한다. 추후 4~5개 힌트로 확장할 수 있게 DB는 JSON 형태를 권장한다.

### 2.2 플레이

1. 플레이어가 닉네임을 입력하고 게임을 시작한다.
2. 힌트 3개가 모두 보인다.
3. 플레이어가 정답 단어를 입력한다.
4. 정답이 틀리면 시도 횟수가 1 증가하고 계속 진행한다.
5. 정답이 맞으면 거짓 힌트를 선택하는 단계로 넘어간다.
6. 거짓 힌트까지 맞히면 `SOLVED`가 된다.
7. 거짓 힌트를 틀리면 `GAVE_UP`으로 처리하고 정답과 거짓 힌트를 공개한다.
8. 포기하면 정답과 거짓 힌트를 공개한다.

### 2.3 시도 횟수

초기 버전에서는 정답 단어 추측만 시도 횟수로 센다.

거짓 힌트 선택은 보너스 판정에 가깝기 때문에 시도 횟수에 포함하지 않는다. 리더보드는 적은 정답 시도 횟수, 빠른 시간 순으로 정렬한다.

### 2.4 승리 조건

`SOLVED` 조건:

- 정답 단어를 맞힘
- 거짓 힌트 번호를 맞힘

`GAVE_UP` 조건:

- 플레이어가 포기함
- 정답은 맞혔지만 거짓 힌트 번호를 틀림

## 3. 데이터 모델

### 3.1 GameType

`GameType`에 새 값을 추가한다.

```java
LIE_HINT
```

### 3.2 TB_GAME 확장

가장 작은 변경으로는 기존 `hint_text`에 JSON 문자열을 넣을 수 있지만, 장기적으로 모드별 설정이 늘어날 가능성이 있으므로 별도 컬럼을 권장한다.

```sql
ALTER TABLE TB_GAME
    ADD COLUMN IF NOT EXISTS game_config JSONB;

ALTER TABLE TB_GAME
    DROP CONSTRAINT IF EXISTS ck_game_type;

ALTER TABLE TB_GAME
    ADD CONSTRAINT ck_game_type
    CHECK (game_type IN ('WORDSIM', 'WORDGUESS', 'LIE_HINT'));
```

`LIE_HINT`의 `game_config` 예시:

```json
{
  "hints": [
    "바다에 산다",
    "포유류다",
    "알을 낳는다"
  ],
  "lieIndex": 2
}
```

`lieIndex`는 0-based index로 저장한다. 프론트 표시에서는 1번, 2번, 3번으로 보여준다.

### 3.3 TB_GUESS_LOG 확장

정답 추측 로그는 기존 구조를 그대로 사용한다.

- `guess_word`: 추측 단어
- `guess_order`: 정답 단어 추측 순서
- `is_correct`: 정답 단어 일치 여부

거짓 힌트 선택 결과를 남기려면 별도 JSON 컬럼을 추가하는 것이 좋다.

```sql
ALTER TABLE TB_GUESS_LOG
    ADD COLUMN IF NOT EXISTS extra_result JSONB;
```

거짓 힌트 선택 로그 예시:

```json
{
  "selectedLieIndex": 2,
  "lieCorrect": true
}
```

초기 구현에서는 거짓 힌트 선택을 마지막 정답 guess 로그의 `extra_result`에 저장한다.

## 4. 백엔드 API 설계

### 4.1 게임 생성

기존 `POST /api/v1/games`를 확장한다.

요청 예시:

```json
{
  "gameType": "LIE_HINT",
  "title": "고래 맞히기",
  "answerWord": "고래",
  "creatorNick": "yono",
  "isPublic": true,
  "lieHints": [
    "바다에 산다",
    "포유류다",
    "알을 낳는다"
  ],
  "lieIndex": 2
}
```

검증:

- `answerWord`: 1~20자
- `lieHints`: 정확히 3개
- 각 힌트: 1~120자
- `lieIndex`: 0~2
- 힌트 중복은 허용하지 않는 것을 권장

### 4.2 게임 조회

기존 `GET /api/v1/games/{gameId}` 응답에 Lie Hint 전용 값을 추가한다.

응답 필드:

```json
{
  "gameType": "LIE_HINT",
  "title": "고래 맞히기",
  "wordLength": 2,
  "creatorNick": "yono",
  "lieHints": [
    "바다에 산다",
    "포유류다",
    "알을 낳는다"
  ],
  "lieHintCount": 3
}
```

주의:

- 플레이 중에는 `lieIndex`를 절대 내려주지 않는다.
- 포기 또는 실패 후에만 정답과 거짓 힌트 번호를 공개한다.

### 4.3 정답 추측

기존 `POST /api/v1/games/{gameId}/guess`를 사용한다.

요청:

```json
{
  "guessWord": "고래"
}
```

정답이 틀린 경우:

```json
{
  "guessWord": "고래",
  "guessOrder": 1,
  "isCorrect": false,
  "status": "IN_PROGRESS",
  "liePhase": false
}
```

정답이 맞은 경우:

```json
{
  "guessWord": "고래",
  "guessOrder": 2,
  "isCorrect": true,
  "status": "IN_PROGRESS",
  "liePhase": true
}
```

`liePhase: true`는 "정답 단어는 맞혔고, 이제 거짓 힌트를 고르라"는 뜻이다.

### 4.4 거짓 힌트 선택

새 API를 추가한다.

```http
POST /api/v1/games/{gameId}/lie-hint
```

요청:

```json
{
  "selectedLieIndex": 2
}
```

성공 응답:

```json
{
  "lieCorrect": true,
  "status": "SOLVED",
  "revealedAnswer": null,
  "revealedLieIndex": null,
  "timeSpentSec": 37
}
```

실패 응답:

```json
{
  "lieCorrect": false,
  "status": "GAVE_UP",
  "revealedAnswer": "고래",
  "revealedLieIndex": 2,
  "timeSpentSec": 42
}
```

### 4.5 포기

기존 `POST /api/v1/games/{gameId}/giveup`를 확장한다.

Lie Hint에서는 `answerWord`와 `revealedLieIndex`를 함께 내려준다.

## 5. 프론트엔드 설계

### 5.1 생성 화면

새 경로:

```text
/create/liehint
```

입력 UI:

- 게임 제목
- 정답 단어
- 힌트 1
- 힌트 2
- 힌트 3
- 거짓 힌트 선택 segmented/radio control
- 출제자 닉네임
- 만들기 버튼

### 5.2 플레이 화면

기존 `/g/[gameId]` 플레이 화면에 `LIE_HINT` 분기를 추가한다.

화면 구성:

- 게임 제목
- 출제자
- 힌트 3개
- 정답 입력창
- 추측 기록
- 정답을 맞힌 뒤 거짓 힌트 선택 UI
- 성공/실패 결과 공유 버튼

### 5.3 리더보드

기존 리더보드 구조를 그대로 사용한다.

정렬 기준:

1. `attempt_count` 오름차순
2. `time_spent_sec` 오름차순

## 6. 구현 순서

1. DB 스키마에 `LIE_HINT`, `game_config`, `extra_result` 추가
2. 백엔드 `GameType`에 `LIE_HINT` 추가
3. `Game` / `GuessLog` 엔티티에 JSONB 컬럼 추가
4. 생성 DTO에 `lieHints`, `lieIndex` 추가
5. `GameService.createGame`에 Lie Hint 검증과 config 저장 추가
6. `GameResponse`에 Lie Hint 공개 필드 추가
7. `GuessService`에 Lie Hint 정답 추측 로직 추가
8. `PlayController`에 `/lie-hint` API 추가
9. `PlayStateResponse`에 Lie Hint 진행 상태 복구 정보 추가
10. 프론트 생성 페이지 `/create/liehint` 추가
11. 홈 화면에 Lie Hint 카드 추가
12. `/g/[gameId]`에 Lie Hint 플레이 UI 추가
13. 공유 결과 텍스트에 Lie Hint 형식 추가
14. 백엔드 테스트와 프론트 타입 체크 실행

## 7. 주의할 점

- `lieIndex`는 게임 중 노출되면 안 된다.
- 정답 단어만 맞힌 상태를 `SOLVED`로 처리하면 안 된다.
- 새 중간 상태가 필요하지만 DB enum을 늘리기보다, `PlayRecord.status=IN_PROGRESS`를 유지하고 "정답 단어는 맞힘" 상태는 로그에서 복구하는 편이 변경 범위가 작다.
- 이미 정답 단어를 맞힌 플레이어가 다시 단어 추측을 하지 못하게 막아야 한다.
- 거짓 힌트 선택은 한 번만 허용한다.
- 기존 WordGuess/WordSim 응답과 타입을 깨지 않도록 새 필드는 nullable로 추가한다.

## 8. 초기 버전 결정

초기 구현은 아래 범위로 제한한다.

- 힌트는 정확히 3개
- 거짓 힌트는 1개
- 정답 단어 검증은 WordSim 사전 검증 없이 일반 텍스트로 처리
- 거짓 힌트 선택은 1회만 가능
- 정답 단어 추측 횟수 제한은 두지 않음
- 리더보드는 완전 성공(`SOLVED`)만 노출

