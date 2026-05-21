'use client';

import { useState } from 'react';

interface Props {
  text: string;          // 클립보드에 복사할 텍스트
  label?: string;
}

/**
 * 결과 텍스트(공유용)를 클립보드에 복사.
 * Web Share API 지원 환경(모바일)에선 시스템 공유 시트도 시도.
 */
export function ShareResultButton({ text, label = '📋 결과 복사' }: Props) {
  const [state, setState] = useState<'idle' | 'copied'>('idle');

  const handleShare = async () => {
    // 1차: Web Share API (모바일에서 카톡/문자 등 직접 공유)
    if (typeof navigator !== 'undefined' && (navigator as any).share) {
      try {
        await (navigator as any).share({ text });
        return;
      } catch {
        // 사용자가 취소했거나 미지원 → 클립보드로 폴백
      }
    }

    // 2차: Clipboard API
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(text);
      } else {
        // 3차: 폴백 (HTTP 환경)
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.focus();
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
      }
      setState('copied');
      setTimeout(() => setState('idle'), 2000);
    } catch {
      window.prompt('결과를 복사하세요:', text);
    }
  };

  return (
    <button
      onClick={handleShare}
      className={`w-full font-bold py-3 px-4 rounded-lg border-2 transition ${
        state === 'copied'
          ? 'bg-hit text-white border-hit'
          : 'bg-white text-gray-700 border-gray-300 hover:border-hit hover:text-hit'
      }`}
    >
      {state === 'copied' ? '✓ 복사됨!' : label}
    </button>
  );
}
