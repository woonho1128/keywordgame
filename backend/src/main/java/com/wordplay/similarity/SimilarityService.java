package com.wordplay.similarity;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WordSim 모드용 단어 임베딩 서비스.
 *
 * - 서버 기동 시 word_vectors.bin 을 메모리에 로드
 * - 단어 → 정규화된 float[] 벡터 매핑 유지
 * - 코사인 유사도 (정규화 벡터 → dot product) 와 순위 계산 제공
 *
 * 파일 포맷:
 *   [i32 vocab_size]
 *   [i32 dimension]
 *   vocab_size 회 반복:
 *     [u16 word_utf8_byte_length]
 *     [N bytes]                  UTF-8 단어
 *     [dimension * f32]          L2 정규화 벡터
 */
@Slf4j
@Service
public class SimilarityService {

    @Value("${app.wordsim.vectors-path:/etc/wordplay/word_vectors.bin}")
    private String vectorsPath;

    private Map<String, float[]> wordToVector = Collections.emptyMap();
    private List<String> vocab = Collections.emptyList();
    private int dimension = 0;
    private boolean loaded = false;

    /** 정답별 참고 점수 (1위/10위/100위/1000위 유사도) 캐시. */
    private final Map<String, ReferenceScores> referenceCache = new ConcurrentHashMap<>();

    public record ReferenceScores(Float top1, Float top10, Float top100, Float top1000) {}

    @PostConstruct
    public void load() {
        Path path = Path.of(vectorsPath);
        if (!Files.exists(path)) {
            log.warn("WordSim vectors file not found: {} (WordSim 모드 비활성화)", path);
            return;
        }
        try {
            doLoad(path);
            loaded = true;
            log.info("WordSim vectors loaded: {} words, {} dimensions", vocab.size(), dimension);
        } catch (IOException e) {
            log.error("Failed to load WordSim vectors from {}", path, e);
        }
    }

    private void doLoad(Path path) throws IOException {
        try (DataInputStream in = new DataInputStream(new FileInputStream(path.toFile()))) {
            int vocabSize = readIntLE(in);
            int dim = readIntLE(in);
            this.dimension = dim;

            Map<String, float[]> map = new HashMap<>(vocabSize * 2);
            List<String> words = new ArrayList<>(vocabSize);

            byte[] wordBuf = new byte[256];
            byte[] vecBuf = new byte[dim * 4];

            for (int i = 0; i < vocabSize; i++) {
                int wordLen = readUShortLE(in);
                if (wordLen > wordBuf.length) wordBuf = new byte[wordLen];
                in.readFully(wordBuf, 0, wordLen);
                String word = new String(wordBuf, 0, wordLen, java.nio.charset.StandardCharsets.UTF_8);

                in.readFully(vecBuf);
                ByteBuffer bb = ByteBuffer.wrap(vecBuf).order(ByteOrder.LITTLE_ENDIAN);
                float[] vec = new float[dim];
                for (int j = 0; j < dim; j++) vec[j] = bb.getFloat();

                map.put(word, vec);
                words.add(word);
            }

            this.wordToVector = map;
            this.vocab = words;
        }
    }

    public boolean isLoaded() {
        return loaded;
    }

    public boolean contains(String word) {
        return wordToVector.containsKey(word);
    }

    public int vocabSize() {
        return vocab.size();
    }

    /** 두 단어의 코사인 유사도. 둘 다 사전에 있어야 함. 없으면 null. */
    public Float cosine(String a, String b) {
        float[] va = wordToVector.get(a);
        float[] vb = wordToVector.get(b);
        if (va == null || vb == null) return null;
        float dot = 0f;
        for (int i = 0; i < va.length; i++) dot += va[i] * vb[i];
        // 정규화된 벡터이므로 dot product = cosine
        // 부동소수 오차로 1.0001 같은 값 방지
        if (dot > 1f) dot = 1f;
        if (dot < -1f) dot = -1f;
        return dot;
    }

    /**
     * 정답 기준 추측 단어의 유사도 순위 (1-based).
     * 정답과 추측 둘 다 사전에 있어야 함.
     */
    public Integer rank(String answer, String guess) {
        float[] va = wordToVector.get(answer);
        float[] vg = wordToVector.get(guess);
        if (va == null || vg == null) return null;

        float guessSim = dot(va, vg);
        int higher = 0;
        for (String w : vocab) {
            if (w.equals(answer)) continue;          // 정답 자체는 순위 제외
            if (w.equals(guess)) continue;            // 자기 자신 제외
            float s = dot(va, wordToVector.get(w));
            if (s > guessSim) higher++;
        }
        return higher + 1;  // 자기보다 큰 게 N개면 N+1등
    }

    /**
     * 정답 기준 참고 점수.
     * 캐시되어 두 번째 호출부터는 O(1).
     */
    public ReferenceScores getReferenceScores(String answer) {
        if (!loaded || !wordToVector.containsKey(answer)) {
            return new ReferenceScores(null, null, null, null);
        }
        return referenceCache.computeIfAbsent(answer, this::computeReferenceScores);
    }

    private ReferenceScores computeReferenceScores(String answer) {
        float[] va = wordToVector.get(answer);
        List<Float> sims = new ArrayList<>(vocab.size());
        for (String w : vocab) {
            if (w.equals(answer)) continue;
            sims.add(dot(va, wordToVector.get(w)));
        }
        sims.sort(Comparator.reverseOrder());
        return new ReferenceScores(
                pick(sims, 0),     // 1위
                pick(sims, 9),     // 10위
                pick(sims, 99),    // 100위
                pick(sims, 999)    // 1000위
        );
    }

    private static Float pick(List<Float> sorted, int idx) {
        return idx < sorted.size() ? sorted.get(idx) : null;
    }

    private static float dot(float[] a, float[] b) {
        float s = 0f;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    private static int readIntLE(DataInputStream in) throws IOException {
        return Integer.reverseBytes(in.readInt());
    }

    private static int readUShortLE(DataInputStream in) throws IOException {
        int hi = in.readUnsignedByte();
        int lo = in.readUnsignedByte();
        return (lo << 8) | hi;  // little endian: low byte first
    }
}
