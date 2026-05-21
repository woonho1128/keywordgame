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

// 초성 — 쌍자음을 같은 자모 2개로 분해
const CHO_DECOMP: string[][] = [
  ['ㄱ'],['ㄱ','ㄱ'],['ㄴ'],['ㄷ'],['ㄷ','ㄷ'],
  ['ㄹ'],['ㅁ'],['ㅂ'],['ㅂ','ㅂ'],['ㅅ'],
  ['ㅅ','ㅅ'],['ㅇ'],['ㅈ'],['ㅈ','ㅈ'],['ㅊ'],
  ['ㅋ'],['ㅌ'],['ㅍ'],['ㅎ'],
];

// 중성 — 이중/복합 모음 모두 기본 자모로 분해
const JUNG_DECOMP: string[][] = [
  ['ㅏ'],['ㅏ','ㅣ'],['ㅑ'],['ㅑ','ㅣ'],
  ['ㅓ'],['ㅓ','ㅣ'],['ㅕ'],['ㅕ','ㅣ'],
  ['ㅗ'],['ㅗ','ㅏ'],['ㅗ','ㅏ','ㅣ'],['ㅗ','ㅣ'],
  ['ㅛ'],
  ['ㅜ'],['ㅜ','ㅓ'],['ㅜ','ㅓ','ㅣ'],['ㅜ','ㅣ'],
  ['ㅠ'],['ㅡ'],['ㅡ','ㅣ'],['ㅣ'],
];

// 종성 — 쌍자음/겹받침 모두 기본 자모로 분해
const JONG_DECOMP: string[][] = [
  [],
  ['ㄱ'],['ㄱ','ㄱ'],['ㄱ','ㅅ'],
  ['ㄴ'],['ㄴ','ㅈ'],['ㄴ','ㅎ'],
  ['ㄷ'],['ㄹ'],['ㄹ','ㄱ'],['ㄹ','ㅁ'],['ㄹ','ㅂ'],['ㄹ','ㅅ'],
  ['ㄹ','ㅌ'],['ㄹ','ㅍ'],['ㄹ','ㅎ'],
  ['ㅁ'],['ㅂ'],['ㅂ','ㅅ'],['ㅅ'],['ㅅ','ㅅ'],
  ['ㅇ'],['ㅈ'],['ㅊ'],['ㅋ'],['ㅌ'],['ㅍ'],['ㅎ'],
];

/** 한 음절을 기본 자모 리스트로 분해 (백엔드 HangulUtil과 동일 규칙). */
export function decomposeSyllable(syllable: string): string[] {
  if (!isHangulSyllable(syllable)) return [];
  const code = syllable.charCodeAt(0) - HANGUL_BASE;
  const cho = Math.floor(code / (JUNG_COUNT * JONG_COUNT));
  const jung = Math.floor((code % (JUNG_COUNT * JONG_COUNT)) / JONG_COUNT);
  const jong = code % JONG_COUNT;
  return [...CHO_DECOMP[cho], ...JUNG_DECOMP[jung], ...JONG_DECOMP[jong]];
}

/** 문자열 전체를 자모 리스트로 분해 (한글 음절만 분해, 나머지 무시). */
export function decomposeText(text: string): string[] {
  const out: string[] = [];
  for (const c of text) {
    if (isHangulSyllable(c)) out.push(...decomposeSyllable(c));
  }
  return out;
}
