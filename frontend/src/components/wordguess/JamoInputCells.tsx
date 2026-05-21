'use client';

import { useRef, useEffect } from 'react';
import clsx from 'clsx';
import { decomposeText } from '@/lib/hangul';

interface Props {
  jamoCount: number;
  value: string;                       // 한글 텍스트 입력 (예: "사과")
  onChange: (text: string) => void;
  onSubmit: () => void;
  disabled?: boolean;
}

/**
 * N칸의 자모 셀로 시각화된 입력 컨테이너.
 *
 * 동작:
 * - 사용자가 한글로 텍스트를 입력하면 자동으로 자모로 분해되어 각 셀에 채워짐
 * - 빈 셀은 점선으로 표시 (입력 가이드)
 * - 클릭하면 숨겨진 input에 포커스가 가서 키보드 IME 사용 가능
 * - Enter로 제출
 *
 * 한글 IME 조합 중에는 분해를 늦춰서 "사" 조합 끝나야 [ㅅ][ㅏ] 표시.
 */
export function JamoInputCells({ jamoCount, value, onChange, onSubmit, disabled }: Props) {
  const hiddenInputRef = useRef<HTMLInputElement>(null);
  const composingRef = useRef(false);

  const jamos = decomposeText(value);
  const cells = Array.from({ length: jamoCount }).map((_, i) => jamos[i] ?? null);

  const focusInput = () => hiddenInputRef.current?.focus();

  useEffect(() => {
    focusInput();
  }, []);

  return (
    <div className="space-y-3">
      {/* 자모 셀 */}
      <div
        onClick={focusInput}
        className="flex flex-wrap gap-1.5 justify-center cursor-text py-2"
        role="textbox"
      >
        {cells.map((jamo, i) => (
          <div
            key={i}
            className={clsx(
              'w-10 h-12 rounded-lg border-2 flex items-center justify-center text-xl font-bold transition',
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
            // IME 조합 중이 아니면 곧바로 부모에 전달.
            // 조합 중이면 onCompositionEnd에서 처리.
            if (!composingRef.current) onChange(e.target.value);
            else onChange(e.target.value); // 컴포지션 중 값도 보여주기 위해 전달은 함
          }}
          onCompositionStart={() => { composingRef.current = true; }}
          onCompositionEnd={(e) => {
            composingRef.current = false;
            onChange((e.target as HTMLInputElement).value);
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !composingRef.current && !disabled) {
              e.preventDefault();
              onSubmit();
            }
          }}
          disabled={disabled}
          autoComplete="off"
          autoCorrect="off"
          autoCapitalize="off"
          spellCheck={false}
          className="flex-1 border border-gray-300 rounded-lg px-3 py-2 text-base"
          placeholder="한글로 추측 (예: 사과)"
        />
        <span className="text-sm text-gray-500 whitespace-nowrap">
          {jamos.length}/{jamoCount}
        </span>
        <button
          type="button"
          onClick={onSubmit}
          disabled={disabled || jamos.length !== jamoCount}
          className="bg-hit text-white font-bold px-6 py-2 rounded-lg hover:opacity-90 disabled:opacity-40 disabled:cursor-not-allowed"
        >
          추측
        </button>
      </div>
    </div>
  );
}
