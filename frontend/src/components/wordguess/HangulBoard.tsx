import { SyllableResult } from '@/lib/hangul';
import { JamoTile } from './JamoTile';

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
      {history.map((h, idx) => {
        // 음절 경계가 드러나면 정답을 유추할 수 있으므로,
        // 모든 자모를 음절 구분 없이 한 줄에 동일한 간격으로 나열한다.
        const marks = h.letterResult.flatMap((r) => r.marks);
        return (
          <div key={idx} className="flex items-center gap-4">
            <span className="text-sm text-gray-400 w-6 text-right">{idx + 1}</span>
            <div className="flex flex-wrap gap-1.5">
              {marks.map((m, i) => (
                <JamoTile key={i} jamo={m.jamo} mark={m.mark} />
              ))}
            </div>
            {h.isCorrect && <span className="text-hit font-bold ml-2">🎉 정답!</span>}
          </div>
        );
      })}
    </div>
  );
}
