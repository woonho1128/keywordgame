'use client';

import { useState } from 'react';

interface Props {
  /** 클릭할 때 카드를 그린다 (미리 그리지 않아 첫 렌더가 가볍다) */
  draw: () => HTMLCanvasElement;
  fileName: string;
  label?: string;
}

/**
 * 결과 카드를 PNG로 저장한다.
 *
 * <p>모바일에선 Web Share로 바로 카톡에 보낼 수 있고, 안 되면 다운로드로 떨어진다.
 * toDataURL(동기)로 뽑아 쓰는 이유: toBlob(비동기) 콜백에서 navigator.share를 부르면
 * iOS가 "사용자 제스처가 아니다"라며 막는다.
 */
export function SaveImageButton({ draw, fileName, label = '🖼 이미지로 저장' }: Props) {
  const [status, setStatus] = useState<'idle' | 'working' | 'done' | 'error'>('idle');

  const handleClick = () => {
    setStatus('working');
    try {
      const blob = toBlob(draw());

      const file = new File([blob], fileName, { type: 'image/png' });
      const shareData = { files: [file] };
      if (navigator.canShare?.(shareData)) {
        navigator
          .share(shareData)
          .then(() => setStatus('done'))
          .catch(() => setStatus('idle')) // 사용자가 공유를 취소한 경우
          .finally(() => resetLater(setStatus));
        return;
      }

      download(blob, fileName);
      setStatus('done');
      resetLater(setStatus);
    } catch {
      setStatus('error');
      resetLater(setStatus);
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

/** dataURL → Blob (동기). 제스처 컨텍스트를 유지하려고 toBlob 대신 쓴다 */
function toBlob(canvas: HTMLCanvasElement): Blob {
  const dataUrl = canvas.toDataURL('image/png');
  const base64 = dataUrl.slice(dataUrl.indexOf(',') + 1);
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return new Blob([bytes], { type: 'image/png' });
}

function download(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  // 다운로드가 시작될 시간을 준 뒤 해제
  setTimeout(() => URL.revokeObjectURL(url), 5000);
}

function resetLater(setStatus: (value: 'idle') => void) {
  setTimeout(() => setStatus('idle'), 2500);
}
