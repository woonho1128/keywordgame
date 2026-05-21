import { SyllableResult, Mark } from './hangul';

const MARK_EMOJI: Record<Mark, string> = {
  H: '🟩',
  M: '🟨',
  S: '⬜',
};

export function formatTime(seconds: number | null | undefined): string {
  if (seconds == null) return '';
  if (seconds < 60) return `${seconds}초`;
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return s === 0 ? `${m}분` : `${m}분 ${s}초`;
}

/**
 * WordGuess 결과를 Wordle 스타일 이모지 그리드로 변환.
 * 각 줄 = 한 번의 추측. 음절은 공백으로 구분.
 *
 * 예: 정답 "사과" (5 자모) 에서:
 *   🟩🟩 ⬜🟨🟨
 *   🟩🟩 🟨🟨🟨
 *   🟩🟩 🟩🟩🟩
 */
export function buildWordGuessGrid(history: Array<{ letterResult: SyllableResult[] }>): string {
  return history.map(h =>
    h.letterResult.map(syl =>
      syl.marks.map(m => MARK_EMOJI[m.mark] ?? '⬜').join('')
    ).join(' ')
  ).join('\n');
}

interface WordGuessShareParams {
  solved: boolean;
  attempts: number;
  maxAttempts: number;
  timeSpentSec: number | null;
  history: Array<{ letterResult: SyllableResult[] }>;
  url: string;
}

export function buildWordGuessShareText(p: WordGuessShareParams): string {
  const statusLine = p.solved
    ? `✅ ${p.attempts}/${p.maxAttempts} 시도에 정답!`
    : `❌ ${p.maxAttempts}회 시도 실패`;
  const timeLine = p.timeSpentSec != null ? ` · ⏱ ${formatTime(p.timeSpentSec)}` : '';
  const grid = buildWordGuessGrid(p.history);

  return `🟩 WordGuess
${statusLine}${timeLine}

${grid}

${p.url}`;
}

interface WordSimShareParams {
  solved: boolean;
  attempts: number;
  timeSpentSec: number | null;
  url: string;
}

export function buildWordSimShareText(p: WordSimShareParams): string {
  const statusLine = p.solved
    ? `✅ ${p.attempts}번 만에 정답!`
    : `❌ ${p.attempts}번 시도 후 포기`;
  const timeLine = p.timeSpentSec != null ? ` · ⏱ ${formatTime(p.timeSpentSec)}` : '';

  return `🔤 WordSim
${statusLine}${timeLine}

${p.url}`;
}
