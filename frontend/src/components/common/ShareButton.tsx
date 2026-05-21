'use client';

import { useState } from 'react';

interface Props {
  url: string;
  label?: string;
}

/**
 * 현재 게임 URL을 클립보드에 복사.
 * 복사 직후 2초간 "복사됨!" 피드백 표시.
 */
export function ShareButton({ url, label = '🔗 URL 복사' }: Props) {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    try {
      // 1차: Clipboard API (최신 브라우저, HTTPS 또는 localhost)
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(url);
      } else {
        // 2차: 폴백 (HTTP 환경 / 구형 브라우저)
        const textarea = document.createElement('textarea');
        textarea.value = url;
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.focus();
        textarea.select();
        document.execCommand('copy');
        document.body.removeChild(textarea);
      }
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // 3차: 복사 실패 시 사용자가 직접 선택할 수 있게 prompt로 노출
      window.prompt('URL을 복사하세요:', url);
    }
  };

  return (
    <button
      onClick={handleCopy}
      className={`w-full font-medium py-2 px-4 rounded-lg border-2 transition ${
        copied
          ? 'bg-hit text-white border-hit'
          : 'bg-white text-gray-700 border-gray-300 hover:border-hit hover:text-hit'
      }`}
    >
      {copied ? '✓ 복사됨!' : label}
    </button>
  );
}
