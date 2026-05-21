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
        <div key={idx} className="flex items-center gap-4">
          <span className="text-sm text-gray-400 w-6 text-right">{idx + 1}</span>
          <div className="flex gap-3">
            {h.letterResult.map((r, i) => (
              <SyllableCell key={i} result={r} />
            ))}
          </div>
          {h.isCorrect && <span className="text-hit font-bold ml-2">🎉 정답!</span>}
        </div>
      ))}
    </div>
  );
}
