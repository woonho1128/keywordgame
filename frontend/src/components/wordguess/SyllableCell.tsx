import { SyllableResult } from '@/lib/hangul';
import { jamoCharsOf } from '@/lib/jamo';
import { JamoTile } from './JamoTile';

interface Props {
  result: SyllableResult;
}

/**
 * 음절 1개 = 자모 3칸 (초/중/종).
 * 종성 없으면 자리 비움.
 */
export function SyllableCell({ result }: Props) {
  const [cho, jung, jong] = jamoCharsOf(result.syllable);

  return (
    <div className="flex flex-col items-center gap-1">
      <div className="text-2xl font-bold mb-1">{result.syllable}</div>
      <div className="flex gap-1">
        <JamoTile jamo={cho} mark={result.cho} />
        <JamoTile jamo={jung} mark={result.jung} />
        <JamoTile jamo={jong} mark={result.jong} />
      </div>
    </div>
  );
}
