import { SyllableResult } from '@/lib/hangul';
import { SyllableCell } from './SyllableCell';

interface Props {
  history: { guessWord: string; letterResult: SyllableResult[]; isCorrect: boolean }[];
}

export function HangulBoard({ history }: Props) {
  if (history.length === 0) {
    return (
      <div className="text-center text-gray-400 py-12">
        아직 시도가 없습니다. 단어를 입력해보세요.
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {history.map((h, idx) => (
        <div key={idx} className="flex items-center gap-2 sm:gap-4">
          <span className="text-sm text-gray-400 w-5 sm:w-6 text-right shrink-0">{idx + 1}</span>
          {/* 좁은 화면에선 음절 단위로 줄바꿈 (음절 안의 자모 타일은 붙어 있게) */}
          <div className="flex flex-wrap items-end gap-x-2 gap-y-2 sm:gap-x-3 min-w-0">
            {h.letterResult.map((r, i) => (
              <SyllableCell key={i} result={r} />
            ))}
            {h.isCorrect && <span className="text-hit font-bold whitespace-nowrap pb-2">🎉 정답!</span>}
          </div>
        </div>
      ))}
    </div>
  );
}
