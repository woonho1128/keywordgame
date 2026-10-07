'use client';

import { useRef, useEffect, useState } from 'react';
import clsx from 'clsx';
import { decomposeText, isAllHangulSyllables } from '@/lib/hangul';

interface Props {
  jamoCount: number;
  value: string;                       // 한글 텍스트 입력 (예: "사과")
  onChange: (text: string) => void;
  onSubmit: (text: string) => void;    // 제출 시점의 입력값 (IME 조합 직후 상태 반영이 늦는 경우 대비)
  disabled?: boolean;
  busy?: boolean;                      // 제출 요청 중 — 입력창은 그대로 두고(모바일 키보드 유지) 제출만 막음
  error?: string | null;               // 서버 검증 에러 — 입력창 바로 아래에 표시
}

/**
 * N칸의 자모 셀로 시각화된 입력 컨테이너.
 *
 * 동작:
 * - 사용자가 한글로 텍스트를 입력하면 자동으로 자모로 분해되어 각 셀에 채워짐
 * - 빈 셀은 점선으로 표시 (입력 가이드)
 * - 셀을 누르면 입력창에 포커스가 가서 키보드 IME 사용 가능
 * - Enter/버튼 모두 자모 수가 맞을 때만 제출, 아니면 입력창 아래에 안내
 *
 * 한글 IME 조합 중 Enter는 무시한다 (조합 확정용 Enter로 제출되지 않게).
 */
export function JamoInputCells({ jamoCount, value, onChange, onSubmit, disabled, busy, error }: Props) {
  const hiddenInputRef = useRef<HTMLInputElement>(null);
  const composingRef = useRef(false);
  const [hint, setHint] = useState<string | null>(null);

  const jamos = decomposeText(value);
  const cells = Array.from({ length: jamoCount }).map((_, i) => jamos[i] ?? null);

  const focusInput = () => hiddenInputRef.current?.focus();

  useEffect(() => {
    focusInput();
  }, []);

  const trySubmit = (text: string) => {
    if (disabled || busy) return;
    const trimmed = text.trim();
    if (trimmed && !isAllHangulSyllables(trimmed)) {
      setHint('완성된 한글 글자로만 입력해주세요 (예: 사과)');
      return;
    }
    const count = decomposeText(trimmed).length;
    if (count !== jamoCount) {
      setHint(`정답은 자모 ${jamoCount}개예요 (지금 ${count}개)`);
      return;
    }
    setHint(null);
    onSubmit(text);
  };

  const message = hint ?? error;

  return (
    <div className="space-y-3">
      {/* 자모 셀 (시각화 전용 — 실제 입력은 아래 input) */}
      <div
        onClick={focusInput}
        className="flex flex-wrap gap-1 sm:gap-1.5 justify-center cursor-text py-2"
        aria-hidden="true"
      >
        {cells.map((jamo, i) => (
          <div
            key={i}
            className={clsx(
              'w-8 h-10 sm:w-10 sm:h-12 rounded-lg border-2 flex items-center justify-center text-lg sm:text-xl font-bold transition',
              jamo
                ? 'border-hit bg-white text-gray-800'
                : 'border-dashed border-gray-300 bg-gray-50 text-gray-300'
            )}
          >
            {jamo ?? '·'}
          </div>
        ))}
      </div>

      {/* 진행도 + 제출 */}
      <div className="flex items-center gap-2">
        <input
          ref={hiddenInputRef}
          type="text"
          value={value}
          onChange={(e) => {
            // 조합 중인 값도 바로 넘겨서 셀 미리보기에 반영
            setHint(null);
            onChange(e.target.value);
          }}
          onCompositionStart={() => { composingRef.current = true; }}
          onCompositionEnd={(e) => {
            composingRef.current = false;
            onChange((e.target as HTMLInputElement).value);
          }}
          onKeyDown={(e) => {
            if (e.key !== 'Enter' || composingRef.current || e.nativeEvent.isComposing) return;
            e.preventDefault();
            trySubmit(e.currentTarget.value);
          }}
          disabled={disabled}
          autoComplete="off"
          autoCorrect="off"
          autoCapitalize="off"
          spellCheck={false}
          // min-w-0: flex 안의 input은 기본 최소폭(약 20글자) 때문에 줄어들지 않아 좁은 화면에서 버튼을 밀어낸다
          className="flex-1 min-w-0 border border-gray-300 rounded-lg px-3 py-2 text-base"
          placeholder="한글로 추측 (예: 사과)"
        />
        <span
          className={clsx(
            'shrink-0 text-sm whitespace-nowrap',
            jamos.length > jamoCount ? 'text-red-500' : 'text-gray-500'
          )}
        >
          {jamos.length}/{jamoCount}
        </span>
        <button
          type="button"
          onClick={() => trySubmit(value)}
          disabled={disabled || busy || jamos.length !== jamoCount}
          className="shrink-0 whitespace-nowrap bg-hit text-white font-bold px-4 sm:px-6 py-2 rounded-lg hover:opacity-90 disabled:opacity-40 disabled:cursor-not-allowed"
        >
          추측
        </button>
      </div>

      {message && <p role="alert" className="text-sm text-red-500">{message}</p>}
    </div>
  );
}
