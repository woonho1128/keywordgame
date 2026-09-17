'use client';

import { useState } from 'react';
import { saveCanvasAsPng } from '@/lib/saveImage';

interface Props {
  /** 클릭할 때 카드를 그린다 (미리 그리지 않아 첫 렌더가 가볍다) */
  draw: () => HTMLCanvasElement;
  fileName: string;
  label?: string;
}

/**
 * 결과 카드를 PNG로 저장한다.
 * PC는 파일 다운로드, 모바일은 공유 시트 — 실제 분기는 lib/saveImage 에 있다.
 */
export function SaveImageButton({ draw, fileName, label = '🖼 이미지로 저장' }: Props) {
  const [status, setStatus] = useState<'idle' | 'working' | 'done' | 'error'>('idle');

  const handleClick = async () => {
    setStatus('working');
    try {
      const outcome = await saveCanvasAsPng(draw(), fileName);
      setStatus(outcome === 'cancelled' ? 'idle' : 'done');
    } catch {
      setStatus('error');
    } finally {
      setTimeout(() => setStatus('idle'), 2500);
    }
  };

  const text =
    status === 'working' ? '만드는 중...' :
    status === 'done' ? '✓ 저장됨!' :
    status === 'error' ? '저장에 실패했어요' :
    label;

  return (
    <button
      onClick={handleClick}
      disabled={status === 'working'}
      className={`w-full font-medium py-2 px-4 rounded-lg border-2 transition ${
        status === 'done'
          ? 'bg-hit text-white border-hit'
          : 'bg-white text-gray-700 border-gray-300 hover:border-hit hover:text-hit'
      } disabled:opacity-60`}
    >
      {text}
    </button>
  );
}
