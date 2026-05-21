/**
 * 백엔드 응답의 letter_result 타입.
 *
 * 백엔드 HangulUtil이 음절을 기본 자모 리스트로 분해하므로
 * (복합 중성/종성 분리 — 꼬들 표준) 각 음절은 가변 길이 marks 리스트를 가진다.
 *
 * 예: "과" → marks = [
 *   { jamo: "ㄱ", kind: "CHO", mark: "H" },
 *   { jamo: "ㅗ", kind: "JUNG", mark: "S" },
 *   { jamo: "ㅏ", kind: "JUNG", mark: "M" },
 * ]
 */

export type Kind = 'CHO' | 'JUNG' | 'JONG';
export type Mark = 'H' | 'M' | 'S';

export type JamoMark = {
  jamo: string;
  kind: Kind;
  mark: Mark;
};

export type SyllableResult = {
  syllable: string;
  marks: JamoMark[];
};

const HANGUL_BASE = 0xac00;
const HANGUL_END = 0xd7a3;

export function isHangulSyllable(c: string): boolean {
  if (c.length !== 1) return false;
  const code = c.charCodeAt(0);
  return code >= HANGUL_BASE && code <= HANGUL_END;
}

export function isAllHangulSyllables(s: string): boolean {
  if (!s) return false;
  for (const c of s) {
    if (!isHangulSyllable(c)) return false;
  }
  return true;
}
