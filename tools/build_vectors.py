#!/usr/bin/env python3
"""
WordSim 벡터 사전 생성 스크립트.

입력:
  - korean_nouns.txt: 한국어 명사 사전 (1줄 1단어, # 주석)

출력:
  - word_vectors.bin: Spring Boot가 로드할 바이너리 파일
    포맷:
      [4 bytes int]   vocab_size
      [4 bytes int]   dimension
      vocab_size 번 반복:
        [2 bytes int]    word UTF-8 byte length
        [N bytes]        word UTF-8 bytes
        [dimension * 4 bytes float32]  vector

모델:
  paraphrase-multilingual-MiniLM-L12-v2 (~471MB, 384차원, 한국어 포함)

실행:
  pip install sentence-transformers numpy
  python build_vectors.py
"""

import os
import struct
import sys
from pathlib import Path

import numpy as np
from sentence_transformers import SentenceTransformer

# 경로 (스크립트 위치 기준)
SCRIPT_DIR = Path(__file__).parent
NOUNS_FILE = SCRIPT_DIR / "korean_nouns.txt"
OUTPUT_FILE = SCRIPT_DIR / "word_vectors.bin"

# 모델 — 다국어 sentence-transformers (한국어 포함, ~471MB)
MODEL_NAME = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"


def load_words(path: Path) -> list[str]:
    """주석/빈 줄 제거하고 중복 없는 단어 리스트 반환."""
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
    """L2 정규화 — 코사인 유사도 계산을 dot product로 단순화."""
    norms = np.linalg.norm(vectors, axis=1, keepdims=True)
    norms[norms == 0] = 1.0  # 0 벡터 방지
    return vectors / norms


def save_binary(words: list[str], vectors: np.ndarray, path: Path):
    """위 docstring의 바이너리 포맷으로 저장."""
    vocab_size, dim = vectors.shape
    with open(path, "wb") as f:
        f.write(struct.pack("<ii", vocab_size, dim))
        for word, vec in zip(words, vectors):
            wb = word.encode("utf-8")
            f.write(struct.pack("<H", len(wb)))
            f.write(wb)
            f.write(vec.astype(np.float32).tobytes())


def main():
    if not NOUNS_FILE.exists():
        print(f"❌ 단어 사전 파일이 없습니다: {NOUNS_FILE}")
        sys.exit(1)

    words = load_words(NOUNS_FILE)
    print(f"📖 단어 로드: {len(words)}개")

    print(f"⏬ 모델 로드 중: {MODEL_NAME}")
    print("   (최초 1회만 다운로드, ~471MB)")
    model = SentenceTransformer(MODEL_NAME)
    print(f"✅ 모델 로드 완료 (차원: {model.get_sentence_embedding_dimension()})")

    print("🔢 임베딩 생성 중...")
    vectors = model.encode(
        words,
        batch_size=64,
        show_progress_bar=True,
        convert_to_numpy=True,
        normalize_embeddings=False,  # 우리가 직접 정규화
    )

    print("🔧 L2 정규화...")
    vectors = normalize(vectors.astype(np.float32))

    print(f"💾 저장: {OUTPUT_FILE}")
    save_binary(words, vectors, OUTPUT_FILE)
    size_mb = OUTPUT_FILE.stat().st_size / 1024 / 1024
    print(f"✅ 완료. 파일 크기: {size_mb:.2f} MB")

    # 간단 검증
    print("\n🧪 검증 샘플:")
    for query in ["사과", "고양이", "사랑", "학교"]:
        if query not in words:
            continue
        i = words.index(query)
        sims = vectors @ vectors[i]  # 정규화돼있으니 dot이 곧 cosine
        top5 = np.argsort(-sims)[1:6]  # 자기 자신 제외
        print(f"  {query} → " + ", ".join(
            f"{words[j]}({sims[j]:.2f})" for j in top5
        ))


if __name__ == "__main__":
    main()
