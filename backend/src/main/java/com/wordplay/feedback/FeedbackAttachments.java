package com.wordplay.feedback;

import com.wordplay.common.exception.BusinessException;
import com.wordplay.common.exception.ErrorCode;
import com.wordplay.feedback.dto.FeedbackRequest;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 첨부 이미지 검증. 받은 base64를 그대로 메일로 넘기므로,
 * 실제 이미지가 맞는지(매직 넘버)와 크기를 여기서 확인한다. 서버에 저장하지는 않는다.
 */
final class FeedbackAttachments {

    static final int MAX_COUNT = 3;
    static final int MAX_BYTES = 2 * 1024 * 1024;        // 장당 2MB
    static final int MAX_TOTAL_BYTES = 4 * 1024 * 1024;  // 합계 4MB

    private FeedbackAttachments() {}

    /** 검증된 첨부 목록(디코드된 바이트 기준). 문제가 있으면 예외. */
    static List<Attachment> validate(List<FeedbackRequest.Image> images) {
        List<Attachment> out = new ArrayList<>();
        if (images == null || images.isEmpty()) return out;
        if (images.size() > MAX_COUNT) throw bad("이미지는 최대 " + MAX_COUNT + "장까지 첨부할 수 있어요");

        long total = 0;
        int idx = 0;
        for (FeedbackRequest.Image img : images) {
            idx++;
            if (img == null || img.content() == null || img.content().isBlank()) continue;
            byte[] bytes;
            try {
                // 혹시 데이터 URL이 통째로 왔으면 접두사를 떼고 디코드한다.
                String raw = img.content();
                int comma = raw.indexOf(",");
                if (raw.startsWith("data:") && comma > 0) raw = raw.substring(comma + 1);
                bytes = Base64.getDecoder().decode(raw.replaceAll("\\s", ""));
            } catch (IllegalArgumentException e) {
                throw bad("이미지를 읽을 수 없습니다(" + idx + "번째)");
            }
            if (bytes.length == 0) continue;
            if (bytes.length > MAX_BYTES) throw bad("이미지 한 장은 2MB를 넘을 수 없어요(" + idx + "번째)");
            total += bytes.length;
            if (total > MAX_TOTAL_BYTES) throw bad("이미지 용량 합계가 너무 큽니다(최대 4MB)");

            String type = sniff(bytes);
            if (type == null) throw bad("이미지 파일만 첨부할 수 있어요(" + idx + "번째)");
            out.add(new Attachment(safeName(img.filename(), idx, type), Base64.getEncoder().encodeToString(bytes)));
        }
        return out;
    }

    /** 매직 넘버로 실제 이미지인지 확인. 아니면 null. */
    private static String sniff(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "png";
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "jpg";
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "gif";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "webp";
        return null;
    }

    /** 경로 문자를 제거한 안전한 파일명. 연속된 점(..)도 남기지 않는다. */
    private static String safeName(String name, int idx, String ext) {
        String fallback = "capture" + idx + "." + ext;
        if (name == null || name.isBlank()) return fallback;
        String n = name.replaceAll("[^A-Za-z0-9._가-힣-]", "_")
                .replaceAll("\\.{2,}", ".")     // .. → .
                .replaceAll("^[._]+", "");      // 앞머리 점·밑줄 제거
        if (n.length() > 60) n = n.substring(n.length() - 60);
        return n.isBlank() ? fallback : n;
    }

    private static BusinessException bad(String m) { return new BusinessException(ErrorCode.INVALID_INPUT, m); }

    /** content = base64. */
    record Attachment(String filename, String content) {}
}
