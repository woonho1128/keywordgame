#!/usr/bin/env python3
"""
WordSim 벡터 사전 생성 — OpenAI 임베딩 버전.

입력:
  - korean_nouns.txt: 한국어 명사 사전 (참고용 단어 풀)
  - 환경변수 OPENAI_API_KEY

출력:
  - word_vectors.bin: Spring Boot가 로드할 바이너리

파일 포맷 (리틀엔디안):
  [i32 vocab_size]
  [i32 dimension]
  vocab_size 회 반복:
    [u16 word_utf8_byte_length]
    [N bytes]                  UTF-8 단어
    [dimension * f32]          L2 정규화 벡터

모델: text-embedding-3-small (1536차원)
비용: 약 3689 단어 × 2 토큰 × $0.02/1M = $0.0001 (사실상 0원)

실행:
  export OPENAI_API_KEY=sk-...
  pip install openai numpy
  python build_vectors.py
"""

import os
import struct
import sys
from pathlib import Path

import numpy as np
from openai import OpenAI

SCRIPT_DIR = Path(__file__).parent
NOUNS_FILE = SCRIPT_DIR / "korean_nouns.txt"
OUTPUT_FILE = SCRIPT_DIR / "word_vectors.bin"

MODEL = "text-embedding-3-small"
BATCH_SIZE = 500   # OpenAI는 한 번에 여러 input 처리 가능


def load_words(path: Path) -> list[str]:
    seen = set()
    words = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            w = line.strip()
            if not w or w.startswith("#"):
                continue
            if w in seen:
                continue
            seen.add(w)
            words.append(w)
    return words


def normalize(vectors: np.ndarray) -> np.ndarray:
    norms = np.linalg.norm(vectors, axis=1, keepdims=True)
    norms[norms == 0] = 1.0
    return vectors / norms


def save_binary(words: list[str], vectors: np.ndarray, path: Path):
    vocab_size, dim = vectors.shape
    with open(path, "wb") as f:
        f.write(struct.pack("<ii", vocab_size, dim))
        for word, vec in zip(words, vectors):
            wb = word.encode("utf-8")
            f.write(struct.pack("<H", len(wb)))
            f.write(wb)
            f.write(vec.astype(np.float32).tobytes())


def main():
    if not os.environ.get("OPENAI_API_KEY"):
        print("❌ OPENAI_API_KEY 환경변수가 설정되지 않았습니다.")
        print("   export OPENAI_API_KEY=sk-... 로 설정하세요.")
        sys.exit(1)

    if not NOUNS_FILE.exists():
        print(f"❌ 단어 사전 파일이 없습니다: {NOUNS_FILE}")
        sys.exit(1)

    words = load_words(NOUNS_FILE)
    print(f"📖 단어 로드: {len(words)}개")

    client = OpenAI()
    print(f"⏬ OpenAI 임베딩 요청: {MODEL}")
    print(f"   (예상 비용: ${len(words) * 2 / 1_000_000 * 0.02:.6f})")

    all_vectors = []
    total_tokens = 0
    for i in range(0, len(words), BATCH_SIZE):
        batch = words[i : i + BATCH_SIZE]
        try:
            resp = client.embeddings.create(input=batch, model=MODEL)
        except Exception as e:
            print(f"❌ OpenAI API 호출 실패: {e}")
            sys.exit(1)
        for item in resp.data:
            all_vectors.append(item.embedding)
        total_tokens += resp.usage.total_tokens
        print(f"  {i + len(batch)} / {len(words)} 완료 (누적 토큰 {total_tokens})")

    vectors = np.array(all_vectors, dtype=np.float32)
    print(f"🔧 L2 정규화 ({vectors.shape})")
    vectors = normalize(vectors)

    print(f"💾 저장: {OUTPUT_FILE}")
    save_binary(words, vectors, OUTPUT_FILE)
    size_mb = OUTPUT_FILE.stat().st_size / 1024 / 1024
    print(f"✅ 완료. 파일 크기: {size_mb:.2f} MB")
    print(f"💰 실사용 토큰: {total_tokens} (비용 ${total_tokens / 1_000_000 * 0.02:.6f})")

    # 검증 샘플
    print("\n🧪 검증 샘플 (정답과 유사한 top5):")
    for query in ["사과", "고양이", "사랑", "학교", "전쟁"]:
        if query not in words:
            continue
        i = words.index(query)
        sims = vectors @ vectors[i]
        top5 = np.argsort(-sims)[1:6]
        print(f"  {query} → " + ", ".join(
            f"{words[j]}({sims[j]:.2f})" for j in top5
        ))


if __name__ == "__main__":
    main()
