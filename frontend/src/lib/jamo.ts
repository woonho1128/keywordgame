/**
 * 한글 자모 표시용 문자 배열.
 * decompose 결과의 인덱스를 사람이 읽는 자모 문자로 변환.
 */

export const CHO_JAMO = [
  'ㄱ','ㄲ','ㄴ','ㄷ','ㄸ','ㄹ','ㅁ','ㅂ','ㅃ','ㅅ',
  'ㅆ','ㅇ','ㅈ','ㅉ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ',
] as const;

export const JUNG_JAMO = [
  'ㅏ','ㅐ','ㅑ','ㅒ','ㅓ','ㅔ','ㅕ','ㅖ','ㅗ','ㅘ',
  'ㅙ','ㅚ','ㅛ','ㅜ','ㅝ','ㅞ','ㅟ','ㅠ','ㅡ','ㅢ','ㅣ',
] as const;

export const JONG_JAMO = [
  '', 'ㄱ','ㄲ','ㄳ','ㄴ','ㄵ','ㄶ','ㄷ','ㄹ','ㄺ',
  'ㄻ','ㄼ','ㄽ','ㄾ','ㄿ','ㅀ','ㅁ','ㅂ','ㅄ','ㅅ',
  'ㅆ','ㅇ','ㅈ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ',
] as const;

import { decompose } from './hangul';

export function jamoCharsOf(syllable: string): [string, string, string | null] {
  const [cho, jung, jong] = decompose(syllable);
  return [
    CHO_JAMO[cho],
    JUNG_JAMO[jung],
    jong === 0 ? null : JONG_JAMO[jong],
  ];
}
