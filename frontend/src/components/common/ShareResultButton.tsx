'use client';

import { useEffect, useState } from 'react';
import { prefersShareSheet } from '@/lib/saveImage';

interface Props {
  text: string;          // 클립보드에 복사할 텍스트
  label?: string;
}

/**
 * 결과 텍스트(공유용)를 클립보드에 복사 — 카톡 등에 붙여넣기 용도.
 *
 * - "복사" 버튼은 기기와 상관없이 항상 클립보드에 복사한다.
 *   (데스크톱 크롬도 Web Share를 지원하지만 윈도우 공유창이 떠서 복사가 안 된 것처럼 보인다)
 * - 휴대폰(터치 기기)에선 시스템 공유 시트를 여는 "공유" 버튼을 따로 보여준다 → 카톡으로 바로 보내기.
 */
export function ShareResultButton({ text, label = '📋 결과 복사' }: Props) {
  const [copied, setCopied] = useState(false);
  const [canShare, setCanShare] = useState(false);

  // navigator / matchMedia는 브라우저에서만 있으므로 마운트 후 판단 (서버 렌더와 어긋나지 않게)
  useEffect(() => {
    setCanShare(typeof navigator.share === 'function' && prefersShareSheet());
  }, []);

  const handleCopy = async () => {
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(text);
      } else {
        // 폴백 (HTTP 환경 / 구형 브라우저)
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
      setCopied(true);
      setTimeout(() => setCopied(false), 2500);
    } catch {
      window.prompt('결과를 복사하세요:', text);
    }
  };

  const handleShare = async () => {
    try {
      await navigator.share({ text });
    } catch {
      // 사용자가 공유 시트를 닫은 경우 — 아무것도 하지 않는다
    }
  };

  return (
    <div className="flex gap-2">
      <button
        onClick={handleCopy}
        className={`flex-1 font-bold py-3 px-4 rounded-lg border-2 transition ${
          copied
            ? 'bg-hit text-white border-hit'
            : 'bg-white text-gray-700 border-gray-300 hover:border-hit hover:text-hit'
        }`}
      >
        {copied ? '✓ 복사됨! 카톡에 붙여넣으세요' : label}
      </button>
      {canShare && (
        <button
          onClick={handleShare}
          className="shrink-0 font-bold py-3 px-5 rounded-lg border-2 bg-white text-gray-700 border-gray-300"
        >
          공유
        </button>
      )}
    </div>
  );
}
