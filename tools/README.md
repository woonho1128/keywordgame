# WordSim 사전 빌드 도구 (OpenAI 버전)

WordSim 모드의 **참고용 사전**을 생성합니다. 게임 자체는 어떤 한국어 단어든
OpenAI Embeddings API를 통해 동적으로 처리하지만, 순위(rank) 계산을 위한
비교 기준이 되는 단어 풀이 필요합니다.

## 흐름

```
[게임 만들 때 / 추측할 때]
사용자 단어 → 백엔드
              ├─ word_vectors.bin 에서 조회 (3000+ 단어)
              ├─ 없으면 OpenAI API 호출 (한 번)
              └─ 결과 메모리 캐시 → 다음부터 0ms

[순위 계산]
사용자 단어 vs word_vectors.bin 의 3000+ 단어
→ 더 가까운 단어가 N개 있으면 N+1위
```

즉 `word_vectors.bin`은 **순위 기준 풀**이고, 실제 게임은 OpenAI 덕분에
무제한 한국어 단어 가능.

## 빌드 (서버에서 1회)

```bash
cd ~/keywordGame/keywordgame/tools

# Python 가상환경
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# OpenAI API 키 설정
export OPENAI_API_KEY=sk-...

# 벡터 생성 (3689 단어 × $0.00000004 = $0.0001, 사실상 무료)
python build_vectors.py

# 결과 확인
ls -lh word_vectors.bin   # ~22MB (1536차원)

# 백엔드가 읽을 위치로 복사
sudo cp word_vectors.bin /etc/wordplay/word_vectors.bin
```

## 백엔드 환경변수 (운영)

`/etc/wordplay/backend.env` 에 추가:
```
OPENAI_API_KEY=sk-...
```

## 단어 사전 수정

`korean_nouns.txt` 편집 → `python build_vectors.py` 재실행 → 새 `word_vectors.bin` 사용.

- 한 줄 한 단어
- `#` 으로 시작하는 줄은 주석
- 중복 자동 제거

## 비용

| 작업 | 비용 |
|------|------|
| 사전 빌드 (3689 단어, 1회) | ~$0.0001 |
| 게임 생성 1회 (사전에 없는 단어 임베딩) | ~$0.0000001 |
| 추측 1회 (사전에 없는 단어) | ~$0.0000001 |
| 친구 1000게임 운영 | < $0.01 |

캐싱 덕에 같은 단어는 한 번만 호출됨.

## 모델

`text-embedding-3-small` — 1536차원, 다국어 (한국어 포함) 우수.

## 파일 포맷 (`word_vectors.bin`)

리틀엔디안:
```
[i32 vocab_size]
[i32 dimension]                  # 1536 (OpenAI 3-small)
vocab_size 회 반복:
  [u16 word_utf8_byte_length]
  [N bytes]                      # UTF-8 단어
  [dimension * f32]              # L2 정규화 벡터
```
