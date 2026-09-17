'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { api } from '@/lib/api';
import {
  EMPTY_PERSON,
  PersonForm,
  SajuReading,
  SajuTypeCode,
  SajuTypeItem,
  SajuTypesResponse,
  toPersonPayload,
  validatePerson,
} from '@/lib/saju';
import { PersonFields } from '@/components/saju/PersonFields';

/** API 호출 실패 시에도 종류 선택은 보여줄 수 있도록 하드코딩 폴백 */
const FALLBACK_TYPES: SajuTypeItem[] = [
  { code: 'TOTAL', label: '종합사주', emoji: '🔮', description: '타고난 기질부터 인생 전반의 흐름까지 한 번에' },
  { code: 'LOVE', label: '연애사주', emoji: '💗', description: '연애 스타일과 인연이 들어오는 흐름' },
  { code: 'WEALTH', label: '재물사주', emoji: '💰', description: '돈이 들어오는 방식과 재물 관리 포인트' },
  { code: 'CAREER', label: '직업사주', emoji: '💼', description: '적성에 맞는 일과 커리어 방향' },
  { code: 'STUDY', label: '공부사주', emoji: '📚', description: '공부 스타일과 시험·합격 흐름' },
  { code: 'HEALTH', label: '건강사주', emoji: '🌿', description: '타고난 체질과 챙겨야 할 부분' },
  { code: 'YEARLY', label: '올해의 운세', emoji: '🗓️', description: '세운(歲運)으로 보는 올해의 흐름' },
];

export default function SajuFormPage() {
  const router = useRouter();

  const [types, setTypes] = useState<SajuTypeItem[]>(FALLBACK_TYPES);
  const [available, setAvailable] = useState(true);
  const [sajuType, setSajuType] = useState<SajuTypeCode>('TOTAL');

  const [person, setPerson] = useState<PersonForm>(EMPTY_PERSON);

  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<SajuTypesResponse>('/api/v1/saju/types')
      .then((data) => {
        if (data.types?.length) setTypes(data.types);
        setAvailable(data.available);
      })
      .catch(() => {
        // 종류 목록은 폴백으로 보여주고, 실패 여부는 제출할 때 알려준다
      });
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    const problem = validatePerson(person, '');
    if (problem) return setError(problem.trim());

    setSubmitting(true);
    try {
      const data = await api<SajuReading>('/api/v1/saju', {
        method: 'POST',
        body: JSON.stringify({ sajuType, ...toPersonPayload(person) }),
      });
      router.push(`/saju/${data.readingId}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : '사주 풀이에 실패했습니다.');
      setSubmitting(false);
    }
  };

  return (
    <main className="min-h-screen p-6 sm:p-8 max-w-xl mx-auto">
      <Link href="/" className="text-sm text-gray-400 hover:text-gray-600">
        ← WordPlay
      </Link>

      <h1 className="text-3xl font-bold mt-3 mb-2">🔮 AI 사주</h1>
      <p className="text-gray-500 mb-6 text-sm leading-relaxed">
        생년월일시로 사주팔자를 계산하고, AI가 원하는 주제로 풀이해드립니다.
        <br />
        사주팔자 계산은 서버가 직접 하고 AI는 해석만 맡습니다.
      </p>

      {!available && (
        <div className="mb-6 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900">
          AI 키가 설정되지 않아 지금은 사주를 볼 수 없습니다. 서버에 <code>OPENAI_API_KEY</code>를
          설정해주세요.
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-6">
        <div>
          <label className="block text-sm font-medium mb-2">어떤 사주를 볼까요? *</label>
          <div className="grid grid-cols-2 gap-2">
            {types.map((type) => (
              <button
                key={type.code}
                type="button"
                onClick={() => setSajuType(type.code)}
                className={`text-left border-2 rounded-lg p-3 transition ${
                  sajuType === type.code
                    ? 'border-hit bg-green-50'
                    : 'border-gray-200 hover:border-gray-300'
                }`}
              >
                <div className="font-bold text-sm">
                  {type.emoji} {type.label}
                </div>
                <div className="text-[11px] text-gray-500 mt-0.5 leading-snug">
                  {type.description}
                </div>
              </button>
            ))}
          </div>
        </div>

        <PersonFields label="내 정보" value={person} onChange={setPerson} namePlaceholder="우노 (비워두면 '고객님')" />

        <p className="text-xs text-gray-400 -mt-2">
          음력 생일만 아신다면 양력으로 변환한 날짜를 넣어주세요. 성별은 대운이 순행인지
          역행인지를 가르는 값이라 결과가 달라집니다.
        </p>

        {error && <p className="text-red-500 text-sm">{error}</p>}

        <button
          type="submit"
          disabled={submitting || !available}
          className="w-full bg-hit text-white font-bold py-3 rounded-lg hover:opacity-90 disabled:opacity-50"
        >
          {submitting ? '사주를 풀이하는 중... (최대 30초)' : '사주 보기'}
        </button>

        <Link
          href="/saju/compat"
          className="block w-full text-center font-medium py-2 px-4 rounded-lg border-2 border-gray-300 text-gray-700 hover:border-move hover:text-move transition"
        >
          💞 두 사람 궁합 보기
        </Link>

        <p className="text-xs text-gray-400 text-center leading-relaxed">
          재미로 보는 콘텐츠입니다. 중요한 결정은 스스로 내려주세요.
          <br />
          입력한 생년월일은 결과를 다시 열어보기 위해 저장됩니다.
        </p>
      </form>
    </main>
  );
}
