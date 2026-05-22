'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { api, GameType } from '@/lib/api';

type CreateResp = {
  gameId: string;
  shareUrl: string;
  title: string;
  creatorNick: string;
  gameType: GameType;
  wordLength: number;
};

export default function CreateLieHintPage() {
  const router = useRouter();
  const [title, setTitle] = useState('');
  const [answerWord, setAnswerWord] = useState('');
  const [hints, setHints] = useState(['', '', '']);
  const [lieIndex, setLieIndex] = useState(0);
  const [creatorNick, setCreatorNick] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const updateHint = (index: number, value: string) => {
    setHints((current) => current.map((hint, i) => (i === index ? value : hint)));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    const cleanHints = hints.map((hint) => hint.trim());
    if (!title.trim()) return setError('게임 제목을 입력해주세요.');
    if (!creatorNick.trim()) return setError('출제자 닉네임을 입력해주세요.');
    if (!answerWord.trim()) return setError('정답 단어를 입력해주세요.');
    if (answerWord.trim().length > 20) return setError('정답은 20자 이하로 입력해주세요.');
    if (cleanHints.some((hint) => !hint)) return setError('힌트 3개를 모두 입력해주세요.');
    if (new Set(cleanHints).size !== cleanHints.length) return setError('힌트는 서로 다르게 입력해주세요.');

    setSubmitting(true);
    try {
      const data = await api<CreateResp>('/api/v1/games', {
        method: 'POST',
        body: JSON.stringify({
          gameType: 'LIE_HINT',
          title: title.trim(),
          answerWord: answerWord.trim(),
          creatorNick: creatorNick.trim(),
          isPublic: true,
          lieHints: cleanHints,
          lieIndex,
        }),
      });
      router.push(`/g/${data.gameId}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : '게임 생성에 실패했습니다.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main className="min-h-screen p-8 max-w-xl mx-auto">
      <h1 className="text-3xl font-bold mb-2">Lie Hint 출제</h1>
      <p className="text-gray-500 mb-8">
        힌트 3개 중 하나를 거짓으로 숨겨두세요. 친구는 정답과 거짓 힌트를 모두 맞혀야 성공합니다.
      </p>

      <form onSubmit={handleSubmit} className="space-y-6">
        <div>
          <label className="block text-sm font-medium mb-1">게임 제목 *</label>
          <input
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="고래 맞히기"
            maxLength={60}
            className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-move"
          />
        </div>

        <div>
          <label className="block text-sm font-medium mb-1">정답 단어 *</label>
          <input
            type="text"
            value={answerWord}
            onChange={(e) => setAnswerWord(e.target.value)}
            placeholder="고래"
            maxLength={20}
            className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-move"
          />
        </div>

        <div className="space-y-3">
          <div className="flex items-center justify-between">
            <label className="block text-sm font-medium">힌트 3개 *</label>
            <span className="text-xs text-gray-400">거짓 힌트를 하나 고르세요</span>
          </div>
          {hints.map((hint, index) => (
            <div key={index} className="flex gap-2">
              <button
                type="button"
                onClick={() => setLieIndex(index)}
                className={`w-12 rounded-lg border font-bold ${
                  lieIndex === index
                    ? 'border-move bg-move text-white'
                    : 'border-gray-300 bg-white text-gray-500'
                }`}
                title={`${index + 1}번 힌트를 거짓으로 지정`}
              >
                {index + 1}
              </button>
              <input
                type="text"
                value={hint}
                onChange={(e) => updateHint(index, e.target.value)}
                placeholder={index === 0 ? '바다에 산다' : index === 1 ? '포유류다' : '알을 낳는다'}
                maxLength={120}
                className="flex-1 border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-move"
              />
            </div>
          ))}
        </div>

        <div>
          <label className="block text-sm font-medium mb-1">출제자 닉네임 *</label>
          <input
            type="text"
            value={creatorNick}
            onChange={(e) => setCreatorNick(e.target.value)}
            placeholder="yono"
            maxLength={50}
            className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:border-move"
          />
        </div>

        {error && <p className="text-red-500 text-sm">{error}</p>}

        <button
          type="submit"
          disabled={submitting}
          className="w-full bg-move text-white font-bold py-3 rounded-lg hover:opacity-90 disabled:opacity-50"
        >
          {submitting ? '생성 중...' : '게임 만들기'}
        </button>
      </form>
    </main>
  );
}
