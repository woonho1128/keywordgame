package com.wordplay.similarity;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
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
 * WordSim 임베딩/유사도 서비스.
 *
 * 두 종류의 벡터 소스:
 *   1) dictionary  — 기동 시 word_vectors.bin 에서 로드 (참고용 단어 풀, 순위 비교 기준)
 *   2) runtimeCache — OpenAI API로 받아 메모리 캐시 (사전에 없는 단어 처리)
 *
 * 단어 조회 순서: dictionary → runtimeCache → OpenAI API (있으면 캐시)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimilarityService {

    @Value("${app.wordsim.vectors-path:/etc/wordplay/word_vectors.bin}")
    private String vectorsPath;

    private final OpenAiEmbeddingClient openAiClient;

    private Map<String, float[]> dictionary = Collections.emptyMap();
    private final Map<String, float[]> runtimeCache = new ConcurrentHashMap<>();
    private List<String> vocab = Collections.emptyList();
    private int dimension = 0;
    private boolean dictionaryLoaded = false;

    private final Map<String, ReferenceScores> referenceCache = new ConcurrentHashMap<>();

    public record ReferenceScores(Float top1, Float top10, Float top100, Float top1000) {}

    @PostConstruct
    public void load() {
        Path path = Path.of(vectorsPath);
        if (!Files.exists(path)) {
            log.warn("WordSim dictionary not found: {} (사전 비활성, OpenAI on-demand만 동작)", path);
            return;
        }
        try {
            doLoad(path);
            dictionaryLoaded = true;
            log.info("WordSim dictionary loaded: {} words, {} dim", vocab.size(), dimension);
        } catch (IOException e) {
            log.error("Failed to load WordSim dictionary from {}", path, e);
        }
        if (!openAiClient.isConfigured()) {
            log.warn("OpenAI API key 미설정 — 사전에 없는 단어는 거부됨");
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

            this.dictionary = map;
            this.vocab = words;
        }
    }

    /** OpenAI 또는 사전 둘 중 하나라도 사용 가능하면 true. */
    public boolean isAvailable() {
        return dictionaryLoaded || openAiClient.isConfigured();
    }

    public int vocabSize() {
        return vocab.size();
    }

    /**
     * 단어 → 벡터.
     * 1) 사전 cache → 2) 런타임 cache → 3) OpenAI API.
     * @return 벡터, 또는 어디서도 못 얻으면 null
     */
    public float[] getVector(String word) {
        float[] v = dictionary.get(word);
        if (v != null) return v;
        v = runtimeCache.get(word);
        if (v != null) return v;
        if (!openAiClient.isConfigured()) return null;
        v = openAiClient.embed(word);
        if (v != null) {
            runtimeCache.put(word, v);
            log.debug("OpenAI embedded new word: {}", word);
        }
        return v;
    }

    /**
     * 단어를 우리 시스템이 처리할 수 있는지 확인.
     * 사전에 있거나 OpenAI에서 임베딩 받을 수 있으면 true.
     */
    public boolean contains(String word) {
        return getVector(word) != null;
    }

    /** 두 단어의 코사인 유사도. 한쪽이라도 못 받으면 null. */
    public Float cosine(String a, String b) {
        float[] va = getVector(a);
        float[] vb = getVector(b);
        if (va == null || vb == null) return null;
        float s = dot(va, vb);
        if (s > 1f) s = 1f;
        if (s < -1f) s = -1f;
        return s;
    }

    /**
     * 정답 기준 추측의 순위 (1-based, 사전 풀 안에서).
     * 사전에서 정답보다 추측에 더 가까운 단어가 N개면 N+1.
     */
    public Integer rank(String answer, String guess) {
        float[] va = getVector(answer);
        float[] vg = getVector(guess);
        if (va == null || vg == null) return null;

        float guessSim = dot(va, vg);
        int higher = 0;
        for (Map.Entry<String, float[]> e : dictionary.entrySet()) {
            String w = e.getKey();
            if (w.equals(answer) || w.equals(guess)) continue;
            float s = dot(va, e.getValue());
            if (s > guessSim) higher++;
        }
        return higher + 1;
    }

    /** 정답 기준 참고 점수 (1위/10위/100위/1000위) — 사전 풀 내 분포. */
    public ReferenceScores getReferenceScores(String answer) {
        float[] va = getVector(answer);
        if (va == null) return new ReferenceScores(null, null, null, null);
        return referenceCache.computeIfAbsent(answer, a -> computeReferenceScores(va, a));
    }

    private ReferenceScores computeReferenceScores(float[] va, String answer) {
        List<Float> sims = new ArrayList<>(dictionary.size());
        for (Map.Entry<String, float[]> e : dictionary.entrySet()) {
            if (e.getKey().equals(answer)) continue;
            sims.add(dot(va, e.getValue()));
        }
        sims.sort(Comparator.reverseOrder());
        return new ReferenceScores(
                pick(sims, 0),
                pick(sims, 9),
                pick(sims, 99),
                pick(sims, 999)
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
        return (lo << 8) | hi;
    }
}
