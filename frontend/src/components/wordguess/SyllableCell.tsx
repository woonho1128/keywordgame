import { SyllableResult } from '@/lib/hangul';
import { JamoTile } from './JamoTile';

interface Props {
  result: SyllableResult;
}

/**
 * 음절 1개를 자모 N개의 타일로 표시.
 * 자모 수는 음절마다 다를 수 있음 (예: 사=2개, 과=3개, 닭=4개).
 */
export function SyllableCell({ result }: Props) {
  return (
    <div className="flex flex-col items-center gap-1">
      <div className="text-2xl font-bold mb-1">{result.syllable}</div>
      <div className="flex gap-1">
        {result.marks.map((m, i) => (
          <JamoTile key={i} jamo={m.jamo} mark={m.mark} />
        ))}
      </div>
    </div>
  );
}
