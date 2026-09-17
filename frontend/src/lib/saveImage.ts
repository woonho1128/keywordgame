/**
 * 캔버스를 PNG로 저장/공유한다.
 *
 * <p>React 컴포넌트에서 분리한 이유: 저장 경로(공유 시트냐 다운로드냐)가 기기마다 갈려서
 * 따로 두고 검증하는 편이 낫다.
 *
 * <p>기기별 동작:
 * <ul>
 *   <li>모바일 — 공유 시트를 띄워 카톡 등으로 바로 보낸다</li>
 *   <li>PC — 곧바로 파일로 내려받는다. 데스크톱 크롬도 Web Share를 지원하지만
 *       윈도우 공유창이 떠서 "저장이 안 된다"처럼 보인다. PC에선 공유를 쓰지 않는다</li>
 * </ul>
 */

export type SaveOutcome = 'shared' | 'downloaded' | 'opened' | 'cancelled';

/** 터치 기기인지 — 공유 시트를 띄울지 판단한다 */
function prefersShareSheet(): boolean {
  if (typeof window === 'undefined') return false;
  return window.matchMedia?.('(pointer: coarse)').matches ?? false;
}

/**
 * dataURL → Blob (동기).
 * toBlob(비동기) 콜백에서 navigator.share를 부르면 iOS가 "사용자 제스처가 아니다"라며 막는다.
 */
export function canvasToBlob(canvas: HTMLCanvasElement): Blob {
  const dataUrl = canvas.toDataURL('image/png');
  const base64 = dataUrl.slice(dataUrl.indexOf(',') + 1);
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return new Blob([bytes], { type: 'image/png' });
}

/**
 * PNG로 저장한다. 반드시 클릭 핸들러 안에서 바로 불러야 한다 (모바일 공유 제약).
 *
 * @returns 어떻게 처리됐는지. 사용자가 공유를 취소하면 'cancelled'
 */
export async function saveCanvasAsPng(
  canvas: HTMLCanvasElement,
  fileName: string
): Promise<SaveOutcome> {
  const blob = canvasToBlob(canvas);

  if (prefersShareSheet()) {
    const file = new File([blob], fileName, { type: 'image/png' });
    const shareData = { files: [file] };
    if (navigator.canShare?.(shareData)) {
      try {
        await navigator.share(shareData);
        return 'shared';
      } catch {
        // 사용자가 공유를 취소했거나 공유가 실패했다 — 다운로드로 떨어진다
        return download(blob, fileName) ? 'downloaded' : 'cancelled';
      }
    }
  }

  if (download(blob, fileName)) return 'downloaded';

  // download 속성을 못 쓰는 환경 — 새 탭으로 띄워 직접 저장하게 한다
  const url = URL.createObjectURL(blob);
  window.open(url, '_blank');
  setTimeout(() => URL.revokeObjectURL(url), 30000);
  return 'opened';
}

function download(blob: Blob, fileName: string): boolean {
  const anchor = document.createElement('a');
  if (!('download' in anchor)) return false;

  const url = URL.createObjectURL(blob);
  anchor.href = url;
  anchor.download = fileName;
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  // 다운로드가 시작될 시간을 준 뒤 해제
  setTimeout(() => URL.revokeObjectURL(url), 10000);
  return true;
}
