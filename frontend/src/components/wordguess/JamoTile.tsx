import clsx from 'clsx';

type Mark = 'H' | 'M' | 'S' | null;

interface Props {
  jamo: string | null;          // 표시할 자모 문자 (예: 'ㅅ')
  mark: Mark;                    // 색상 마커
}

/**
 * 자모 한 개를 표시하는 작은 타일.
 * - H: 초록 + ✓
 * - M: 노랑 + ↔
 * - S: 회색 + ✗
 * - null: 빈 칸 (자모 없음)
 */
export function JamoTile({ jamo, mark }: Props) {
  const colorClass =
    mark === 'H' ? 'bg-hit text-white border-hit' :
    mark === 'M' ? 'bg-move text-white border-move' :
    mark === 'S' ? 'bg-skip text-white border-skip' :
    'bg-white text-gray-400 border-gray-200';

  const icon = mark === 'H' ? '✓' : mark === 'M' ? '↔' : mark === 'S' ? '✗' : '';

  return (
    <div className={clsx(
      'flex flex-col items-center justify-center w-10 h-10 border-2 rounded text-sm font-bold',
      colorClass
    )}>
      <span className="text-base leading-none">{jamo ?? '·'}</span>
      {mark && <span className="text-[10px] opacity-80 leading-none mt-0.5">{icon}</span>}
    </div>
  );
}
