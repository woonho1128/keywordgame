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
  forwardLuck: boolean;
  luckStartAge: number;
  luckCycles: LuckCycleView[];
  currentLuck: string | null;
  yearlyLuck: string;
  yearlyLuckYear: number;
};

export type SajuResult = {
  headline: string | null;
  summary: string;
  sections: { title: string; body: string }[];
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
