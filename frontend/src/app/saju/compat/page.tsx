'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { api } from '@/lib/api';
import {
  CompatReading,
  CompatTypeCode,
  CompatTypeItem,
  CompatTypesResponse,
  EMPTY_PERSON,
  PersonForm,
  toPersonPayload,
  validatePerson,
} from '@/lib/saju';
import { PersonFields } from '@/components/saju/PersonFields';

/** API 호출이 실패해도 종류 선택은 보이도록 하드코딩 폴백 */
const FALLBACK_TYPES: CompatTypeItem[] = [
  { code: 'LOVE', label: '연인궁합', emoji: '💕', description: '연애할 때 두 사람이 어떻게 맞물리는지' },
  { code: 'COUPLE', label: '부부궁합', emoji: '💍', description: '오래 같이 살 때의 합' },
  { code: 'FRIEND', label: '친구궁합', emoji: '🤝', description: '친구로 지낼 때의 케미' },
  { code: 'WORK', label: '동료궁합', emoji: '💼', description: '같이 일할 때의 합' },
  { code: 'FAMILY', label: '가족궁합', emoji: '👨‍👩‍👧', description: '가족으로 엮인 사이의 합' },
];

export default function CompatFormPage() {
  const router = useRouter();

  const [types, setTypes] = useState<CompatTypeItem[]>(FALLBACK_TYPES);
  const [available, setAvailable] = useState(true);
  const [compatType, setCompatType] = useState<CompatTypeCode>('LOVE');

  const [personA, setPersonA] = useState<PersonForm>(EMPTY_PERSON);
  const [personB, setPersonB] = useState<PersonForm>({ ...EMPTY_PERSON, gender: 'FEMALE' });

  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<CompatTypesResponse>('/api/v1/saju/compat/types')
      .then((data) => {
        if (data.types?.length) setTypes(data.types);
        setAvailable(data.available);
      })
      .catch(() => {
        // 목록은 폴백으로 보여주고, 실패 여부는 제출할 때 알려준다
      });
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    const problem = validatePerson(personA, '첫 번째 분') ?? validatePerson(personB, '두 번째 분');
    if (problem) return setError(problem);

    setSubmitting(true);
    try {
      const data = await api<CompatReading>('/api/v1/saju/compat', {
        method: 'POST',
        body: JSON.stringify({
          compatType,
          personA: toPersonPayload(personA),
          personB: toPersonPayload(personB),
        }),
      });
      router.push(`/saju/compat/${data.compatId}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : '궁합 풀이에 실패했습니다.');
      setSubmitting(false);
    }
  };

  return (
    <main className="min-h-screen p-6 sm:p-8 max-w-xl mx-auto">
      <Link href="/saju" className="text-sm text-gray-400 hover:text-gray-600">
        ← AI 사주
      </Link>

      <h1 className="text-3xl font-bold mt-3 mb-2">💞 궁합 보기</h1>
      <p className="text-gray-500 mb-6 text-sm leading-relaxed">
        두 사람의 사주를 맞대어 봅니다. 일지(배우자 자리)의 합·충, 천간합, 오행 보완까지
        서버가 계산하고 AI가 풀이합니다.
      </p>

      {!available && (
        <div className="mb-6 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900">
          AI 키가 설정되지 않아 지금은 궁합을 볼 수 없습니다.
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-6">
        <div>
          <label className="block text-sm font-medium mb-2">어떤 사이인가요? *</label>
          <div className="grid grid-cols-2 gap-2">
            {types.map((type) => (
              <button
                key={type.code}
                type="button"
                onClick={() => setCompatType(type.code)}
                className={`text-left border-2 rounded-lg p-3 transition ${
                  compatType === type.code
                    ? 'border-move bg-amber-50'
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

        <PersonFields
          label="첫 번째 분"
          value={personA}
          onChange={setPersonA}
          namePlaceholder="나"
          accent="move"
        />

        <div className="text-center text-2xl text-gray-300">💞</div>

        <PersonFields
          label="두 번째 분"
          value={personB}
          onChange={setPersonB}
          namePlaceholder="상대방"
          accent="move"
        />

        {error && <p className="text-red-500 text-sm">{error}</p>}

        <button
          type="submit"
          disabled={submitting || !available}
          className="w-full bg-move text-white font-bold py-3 rounded-lg hover:opacity-90 disabled:opacity-50"
        >
          {submitting ? '두 사주를 맞춰보는 중... (최대 40초)' : '궁합 보기'}
        </button>

        <p className="text-xs text-gray-400 text-center leading-relaxed">
          재미로 보는 콘텐츠입니다. 관계의 중요한 결정은 두 분이 직접 내려주세요.
          <br />
          입력한 생년월일은 결과를 다시 열어보기 위해 저장됩니다.
        </p>
      </form>
    </main>
  );
}
