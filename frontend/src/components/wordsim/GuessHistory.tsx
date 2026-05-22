import { ReactNode } from 'react';
import { SimilarityBar } from './SimilarityBar';

export type WordSimGuess = {
  guessWord: string;
  similarity: number;
  rank: number | null;
  isCorrect: boolean;
};

interface Props {
  history: WordSimGuess[];
  lastGuess?: WordSimGuess;   // 가장 최근 시도 (강조 표시용)
  inputSlot?: ReactNode;      // '방금 시도' 카드와 단어 리스트 사이에 들어갈 입력 영역
}

/**
 * WordSim 추측 이력 표시.
 * 가장 최근 추측을 상단에 강조해서 보여주고,
 * 그 아래 입력 영역, 그 아래에 유사도 높은 순서로 모든 시도 정렬.
 */
export function GuessHistory({ history, lastGuess, inputSlot }: Props) {
  // 유사도 내림차순 정렬
  const sorted = [...history].sort((a, b) => b.similarity - a.similarity);

  return (
    <div className="space-y-3">
      {lastGuess && (
        <div className="border-2 border-move rounded-lg p-3 bg-yellow-50">
          <div className="flex items-center gap-3 mb-2">
            <span className="text-xs text-gray-500">방금 시도:</span>
            <span className="text-lg font-bold">{lastGuess.guessWord}</span>
          </div>
          <SimilarityBar
            similarity={lastGuess.similarity}
            rank={lastGuess.rank}
            isCorrect={lastGuess.isCorrect}
          />
        </div>
      )}

      {inputSlot}

      {history.length === 0 ? (
        <div className="text-center text-gray-400 py-12">
          아직 시도가 없습니다. 단어를 입력해보세요.
        </div>
      ) : (
      <table className="w-full">
        <thead className="text-left text-xs text-gray-400 border-b">
          <tr>
            <th className="py-2 w-12">#</th>
            <th>단어</th>
            <th className="w-1/2">유사도</th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((g, i) => (
            <tr key={`${g.guessWord}-${i}`} className="border-b last:border-b-0">
              <td className="py-2 text-gray-400">{i + 1}</td>
              <td className="font-medium">{g.guessWord}</td>
              <td className="py-2">
                <SimilarityBar similarity={g.similarity} rank={g.rank} isCorrect={g.isCorrect} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      )}
    </div>
  );
}
