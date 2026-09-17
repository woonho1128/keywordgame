/**
 * AI 사주 타입 정의 + 표시용 헬퍼.
 * 백엔드 com.wordplay.saju.dto 와 1:1로 맞춘다.
 */

export type SajuTypeCode =
  | 'TOTAL'
  | 'LOVE'
  | 'WEALTH'
  | 'CAREER'
  | 'STUDY'
  | 'HEALTH'
  | 'YEARLY';

export type SajuTypeItem = {
  code: SajuTypeCode;
  label: string;
  emoji: string;
  description: string;
};

export type SajuTypesResponse = {
  available: boolean;
  types: SajuTypeItem[];
};

export type PillarView = {
  position: string;
  stem: string;
  stemHanja: string;
  stemElement: string;
  branch: string;
  branchHanja: string;
  branchElement: string;
  zodiac: string;
  stemTenGod: string;
  branchTenGod: string;
  hiddenStems: string[] | null;
};

export type LuckCycleView = {
  order: number;
  startAge: number;
  endAge: number;
  pillar: string;
  pillarHanja: string;
  current: boolean;
};

export type SajuChart = {
  pillars: PillarView[];
  dayMaster: string;
  dayMasterHanja: string;
  dayMasterElement: string;
  zodiac: string;
  koreanAge: number;
  hourKnown: boolean;
  elementCounts: Record<string, number>;
  missingElements: string[];
  strongestElement: string;
  tenGodGroups: Record<string, number> | null;
  bodyStrength: string | null;
  monthSupport: boolean;
  forwardLuck: boolean;
  luckStartAge: number;
  luckCycles: LuckCycleView[];
  currentLuck: string | null;
  yearlyLuck: string;
  yearlyLuckYear: number;
};

export type Section = { title: string; body: string };

/** strengths·cautions·timeline 은 나중에 추가된 필드라 예전 결과엔 없다 */
export type SajuResult = {
  headline: string | null;
  summary: string;
  sections: Section[];
  strengths: string[] | null;
  cautions: string[] | null;
  timeline: { period: string; body: string }[] | null;
  keywords: string[] | null;
  lucky: {
    color: string | null;
    number: string | null;
    direction: string | null;
    item: string | null;
  } | null;
  advice: string | null;
  score: number | null;
};

export type SajuReading = {
  readingId: string;
  shareUrl: string;
  sajuType: SajuTypeCode;
  typeLabel: string;
  typeEmoji: string;
  nickname: string | null;
  birthDate: string;
  birthTimeLabel: string;
  gender: string;
  chart: SajuChart;
  result: SajuResult;
  createdAt: string;
};

/** 오행별 색상 — Tailwind는 클래스명을 정적으로 뽑으므로 문자열을 통째로 둔다 */
const ELEMENT_STYLE: Record<string, { chip: string; bar: string; text: string }> = {
  목: { chip: 'bg-green-100 border-green-300 text-green-800', bar: 'bg-green-500', text: 'text-green-700' },
  화: { chip: 'bg-red-100 border-red-300 text-red-800', bar: 'bg-red-500', text: 'text-red-700' },
  토: { chip: 'bg-amber-100 border-amber-300 text-amber-900', bar: 'bg-amber-500', text: 'text-amber-800' },
  금: { chip: 'bg-slate-100 border-slate-300 text-slate-700', bar: 'bg-slate-500', text: 'text-slate-700' },
  수: { chip: 'bg-blue-100 border-blue-300 text-blue-800', bar: 'bg-blue-500', text: 'text-blue-700' },
};

const ELEMENT_FALLBACK = {
  chip: 'bg-gray-100 border-gray-300 text-gray-700',
  bar: 'bg-gray-400',
  text: 'text-gray-600',
};

export function elementStyle(element: string) {
  return ELEMENT_STYLE[element] ?? ELEMENT_FALLBACK;
}

/** 오행 순서 고정 (목화토금수) */
export const ELEMENT_ORDER = ['목', '화', '토', '금', '수'];

// ---------------------------------------------------------------------
// 궁합
// ---------------------------------------------------------------------

export type CompatTypeCode = 'LOVE' | 'COUPLE' | 'FRIEND' | 'WORK' | 'FAMILY';

export type CompatTypeItem = {
  code: CompatTypeCode;
  label: string;
  emoji: string;
  description: string;
};

export type CompatTypesResponse = {
  available: boolean;
  types: CompatTypeItem[];
};

export type CompatSignal = {
  position: string;
  relation: string;
  detail: string;
  positive: boolean;
};

export type CompatAnalysis = {
  score: number;
  aName: string;
  bName: string;
  aChart: SajuChart;
  bChart: SajuChart;
  aSeesB: string;
  bSeesA: string;
  dayStemHap: string | null;
  aFilledByB: string[];
  bFilledByA: string[];
  signals: CompatSignal[];
};

export type CompatResult = {
  headline: string | null;
  summary: string;
  sections: Section[];
  strengths: string[] | null;
  cautions: string[] | null;
  aToB: string | null;
  bToA: string | null;
  keywords: string[] | null;
  advice: string | null;
};

export type CompatReading = {
  compatId: string;
  shareUrl: string;
  compatType: CompatTypeCode;
  typeLabel: string;
  typeEmoji: string;
  analysis: CompatAnalysis;
  result: CompatResult;
  createdAt: string;
};

/** 한 사람 입력 폼의 상태 */
export type PersonForm = {
  nickname: string;
  birthDate: string;
  birthTime: string;
  timeUnknown: boolean;
  gender: 'MALE' | 'FEMALE';
};

export const EMPTY_PERSON: PersonForm = {
  nickname: '',
  birthDate: '',
  birthTime: '',
  timeUnknown: false,
  gender: 'MALE',
};

/** 폼 상태 → API 요청 바디 */
export function toPersonPayload(person: PersonForm) {
  return {
    nickname: person.nickname.trim() || null,
    birthDate: person.birthDate,
    // 브라우저에 따라 "HH:mm:ss"로 오므로 분까지만 보낸다
    birthTime: person.timeUnknown ? null : person.birthTime.slice(0, 5),
    timeUnknown: person.timeUnknown,
    gender: person.gender,
  };
}

/** 생년월일·시각 공통 검증. 문제가 없으면 null */
export function validatePerson(person: PersonForm, who: string): string | null {
  if (!person.birthDate) return `${who} 생년월일을 입력해주세요.`;
  if (!person.timeUnknown && !person.birthTime) {
    return `${who} 태어난 시각을 입력하거나 "시간을 몰라요"를 선택해주세요.`;
  }
  const year = Number(person.birthDate.slice(0, 4));
  if (year < 1901 || year > 2099) return `${who} 생년월일은 1901~2099년만 지원합니다.`;
  if (person.birthDate > new Date().toISOString().slice(0, 10)) {
    return `${who} 생년월일이 미래로 되어 있어요.`;
  }
  return null;
}
