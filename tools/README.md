# WordSim 사전 빌드 도구

WordSim 모드가 사용하는 한국어 단어 임베딩 사전을 생성합니다.
한 번만 실행하면 되고, 결과 파일(`word_vectors.bin`)을 Spring Boot가 로드합니다.

## 한 번에 실행 (서버에서)

```bash
cd ~/keywordGame/keywordgame/tools

# Python 패키지 설치 (가상환경 권장)
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# 벡터 생성 (모델 다운로드 + 임베딩 ~5분)
python build_vectors.py

# 결과 파일 확인
ls -lh word_vectors.bin

# Spring Boot가 읽을 위치로 복사
mkdir -p /etc/wordplay
sudo cp word_vectors.bin /etc/wordplay/word_vectors.bin
sudo chmod 644 /etc/wordplay/word_vectors.bin

# 백엔드 재시작
sudo systemctl restart wordplay-backend
```

## 단어 사전 수정

`korean_nouns.txt` 편집 → `python build_vectors.py` 재실행 → 새 `word_vectors.bin` 사용.

- 한 줄 한 단어
- `#` 으로 시작하는 줄은 주석
- 중복 자동 제거

## 모델

`sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2`
- 384차원
- ~471MB
- 한국어 포함 다국어 모델
- 최초 실행 시 자동 다운로드 (~/.cache/huggingface)

## 파일 포맷 (`word_vectors.bin`)

리틀엔디안 바이너리:
```
[i32 vocab_size]
[i32 dimension]
vocab_size 회 반복:
  [u16 word_utf8_byte_length]
  [N  bytes]                       # UTF-8 단어
  [dimension * f32]                # L2 정규화된 벡터
```

벡터는 L2 정규화되어 있어서 코사인 유사도가 곧 dot product와 같습니다.
