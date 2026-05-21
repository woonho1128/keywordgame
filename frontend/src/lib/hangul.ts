/**
 * 한글 음절 분해 (프론트엔드용).
 * 백엔드 HangulUtil과 동일 로직 — 클라이언트에서 미리 검증/표시할 때 사용.
 */

const HANGUL_BASE = 0xac00;
const HANGUL_END = 0xd7a3;
const JUNG_COUNT = 21;
const JONG_COUNT = 28;

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

export function decompose(syllable: string): [number, number, number] {
  const code = syllable.charCodeAt(0) - HANGUL_BASE;
  const cho = Math.floor(code / (JUNG_COUNT * JONG_COUNT));
  const jung = Math.floor((code % (JUNG_COUNT * JONG_COUNT)) / JONG_COUNT);
  const jong = code % JONG_COUNT;
  return [cho, jung, jong];
}

/** 백엔드 응답의 letter_result 타입 */
export type SyllableResult = {
  syllable: string;
  cho: 'H' | 'M' | 'S' | null;
  jung: 'H' | 'M' | 'S' | null;
  jong: 'H' | 'M' | 'S' | null;
};
