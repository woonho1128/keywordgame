import clsx from 'clsx';
import { Mark } from '@/lib/hangul';

interface Props {
  jamo: string;
  mark: Mark;
}

/**
 * 자모 한 개 타일.
 * - H: 초록 + ✓
 * - M: 노랑 + ↔
 * - S: 회색 + ✗
 */
export function JamoTile({ jamo, mark }: Props) {
  const colorClass =
    mark === 'H' ? 'bg-hit text-white border-hit' :
    mark === 'M' ? 'bg-move text-white border-move' :
    'bg-skip text-white border-skip';

  const icon = mark === 'H' ? '✓' : mark === 'M' ? '↔' : '✗';

  return (
    <div className={clsx(
      'flex flex-col items-center justify-center w-10 h-10 border-2 rounded text-sm font-bold',
      colorClass
    )}>
      <span className="text-base leading-none">{jamo}</span>
      <span className="text-[10px] opacity-80 leading-none mt-0.5">{icon}</span>
    </div>
  );
}
