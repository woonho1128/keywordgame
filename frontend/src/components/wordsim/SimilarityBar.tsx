import clsx from 'clsx';

interface Props {
  similarity: number;   // 0~1
  rank: number | null;
  isCorrect: boolean;
}

/**
 * 유사도 가로 막대 + 점수 + 순위.
 * 색상은 유사도에 따라 단계별 (낮음 회색 → 중간 노랑 → 높음 초록).
 */
export function SimilarityBar({ similarity, rank, isCorrect }: Props) {
  const percent = Math.max(0, Math.min(100, similarity * 100));
  const score = (similarity * 100).toFixed(2);

  const barColor =
    isCorrect ? 'bg-hit' :
    percent >= 60 ? 'bg-hit' :
    percent >= 40 ? 'bg-move' :
    percent >= 20 ? 'bg-yellow-300' :
    'bg-gray-300';

  return (
    <div className="flex items-center gap-3 w-full">
      <div className="flex-1 relative bg-gray-100 rounded-full h-6 overflow-hidden">
        <div
          className={clsx('h-full transition-all rounded-full', barColor)}
          style={{ width: `${percent}%` }}
        />
        <div className="absolute inset-0 flex items-center justify-end pr-3 text-xs font-bold text-gray-700">
          {score}점
        </div>
      </div>
      <div className="text-sm text-gray-500 whitespace-nowrap min-w-[80px] text-right">
        {isCorrect ? (
          <span className="font-bold text-hit">🎉 정답!</span>
        ) : rank != null && rank <= 1000 ? (
          <span><span className="font-bold">{rank}</span>위</span>
        ) : (
          <span className="text-gray-400">1000위 밖</span>
        )}
      </div>
    </div>
  );
}
