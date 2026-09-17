'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { api } from '@/lib/api';
import { SajuReading } from '@/lib/saju';
import { SajuChartView } from '@/components/saju/SajuChartView';
import { SaveImageButton } from '@/components/saju/SaveImageButton';
import { ShareButton } from '@/components/common/ShareButton';
import { drawSajuCard } from '@/lib/sajuCard';

export default function SajuResultPage({ params }: { params: { readingId: string } }) {
  const [reading, setReading] = useState<SajuReading | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [shareUrl, setShareUrl] = useState('');

  useEffect(() => {
    setShareUrl(window.location.href);
    api<SajuReading>(`/api/v1/saju/${params.readingId}`)
      .then(setReading)
      .catch((err) => setError(err instanceof Error ? err.message : '결과를 불러오지 못했습니다.'));
  }, [params.readingId]);

  if (error) {
    return (
      <main className="min-h-screen p-8 max-w-xl mx-auto">
        <p className="text-red-500">{error}</p>
        <Link href="/saju" className="text-sm text-gray-500 underline mt-4 inline-block">
          사주 다시 보기
        </Link>
      </main>
    );
  }

  if (!reading) {
    return (
      <main className="min-h-screen p-8 max-w-xl mx-auto">
        <p className="text-gray-400">사주를 펼치는 중...</p>
      </main>
    );
  }

  const { result, chart } = reading;
  const score = result.score ?? null;

  return (
    <main className="min-h-screen p-5 sm:p-8 max-w-xl mx-auto">
      <Link href="/saju" className="text-sm text-gray-400 hover:text-gray-600">
        ← 다른 사주 보기
      </Link>

      {/* 헤더 */}
      <header className="mt-3 mb-6">
        <div className="text-sm text-gray-500">
          {reading.typeEmoji} {reading.typeLabel}
        </div>
        <h1 className="text-2xl sm:text-3xl font-bold mt-1 leading-snug">
          {result.headline || reading.typeLabel}
        </h1>
        <p className="text-sm text-gray-500 mt-2">
          {reading.nickname ? `${reading.nickname} · ` : ''}
          {reading.birthDate} · {reading.birthTimeLabel} · {reading.gender}
        </p>
      </header>

      {/* 점수 */}
      {score !== null && (
        <section className="mb-6">
          <div className="flex items-baseline justify-between mb-1">
            <span className="text-sm font-medium text-gray-600">{reading.typeLabel} 점수</span>
            <span className="text-2xl font-bold">{score}점</span>
          </div>
          <div className="h-3 bg-gray-100 rounded-full overflow-hidden">
            <div
              className="h-full bg-hit rounded-full transition-all"
              style={{ width: `${Math.min(100, Math.max(0, score))}%` }}
            />
          </div>
        </section>
      )}

      {/* 요점 — 한눈에 보는 답 (미래인연이면 횟수·자리·시기) */}
      {result.highlights && result.highlights.length > 0 && (
        <section className="mb-6 grid grid-cols-1 sm:grid-cols-3 gap-2">
          {result.highlights.map((highlight, index) => (
            <div key={index} className="rounded-xl border-2 border-gray-200 p-3">
              <div className="text-xs text-gray-500">{highlight.label}</div>
              <div className="text-xl font-bold mt-0.5 leading-snug">{highlight.value}</div>
              {highlight.detail && (
                <p className="text-xs text-gray-500 mt-1 leading-relaxed">{highlight.detail}</p>
              )}
            </div>
          ))}
        </section>
      )}

      {/* 요약 */}
      <section className="mb-6 rounded-xl bg-gray-50 p-4 sm:p-5">
        <p className="leading-relaxed whitespace-pre-line">{result.summary}</p>
      </section>

      {/* 키워드 */}
      {result.keywords && result.keywords.length > 0 && (
        <div className="mb-6 flex flex-wrap gap-2">
          {result.keywords.map((keyword) => (
            <span
              key={keyword}
              className="px-3 py-1 rounded-full bg-white border border-gray-300 text-sm text-gray-700"
            >
              #{keyword}
            </span>
          ))}
        </div>
      )}

      {/* 사주팔자 */}
      <div className="mb-6">
        <SajuChartView chart={chart} />
      </div>

      {/* 해석 섹션 */}
      <section className="space-y-4 mb-6">
        {result.sections.map((section, index) => (
          <article key={index} className="border-l-4 border-hit pl-4">
            <h2 className="font-bold mb-1">{section.title}</h2>
            <p className="text-gray-700 leading-relaxed whitespace-pre-line">{section.body}</p>
          </article>
        ))}
      </section>

      {/* 강점 / 주의점 */}
      {((result.strengths?.length ?? 0) > 0 || (result.cautions?.length ?? 0) > 0) && (
        <section className="mb-6 grid grid-cols-1 sm:grid-cols-2 gap-3">
          {result.strengths && result.strengths.length > 0 && (
            <div className="rounded-xl border-2 border-hit/40 bg-green-50/50 p-4">
              <h2 className="font-bold text-hit mb-2">타고난 강점</h2>
              <ul className="space-y-2">
                {result.strengths.map((item, index) => (
                  <li key={index} className="text-sm text-gray-700 leading-relaxed flex gap-2">
                    <span className="text-hit shrink-0">✔</span>
                    <span>{item}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
          {result.cautions && result.cautions.length > 0 && (
            <div className="rounded-xl border-2 border-move/50 bg-amber-50/50 p-4">
              <h2 className="font-bold text-move mb-2">조심할 점</h2>
              <ul className="space-y-2">
                {result.cautions.map((item, index) => (
                  <li key={index} className="text-sm text-gray-700 leading-relaxed flex gap-2">
                    <span className="text-move shrink-0">!</span>
                    <span>{item}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </section>
      )}

      {/* 시기별 흐름 */}
      {result.timeline && result.timeline.length > 0 && (
        <section className="mb-6">
          <h2 className="font-bold mb-3">시기별 흐름</h2>
          <ol className="relative border-l-2 border-gray-200 ml-2 space-y-4">
            {result.timeline.map((period, index) => (
              <li key={index} className="pl-4 relative">
                <span className="absolute -left-[7px] top-1.5 w-3 h-3 rounded-full bg-hit" />
                <div className="text-sm font-bold">{period.period}</div>
                <p className="text-sm text-gray-700 leading-relaxed mt-0.5">{period.body}</p>
              </li>
            ))}
          </ol>
        </section>
      )}

      {/* 행운 정보 */}
      {result.lucky && (
        <section className="mb-6 grid grid-cols-2 gap-2">
          {[
            { label: '행운의 색', value: result.lucky.color },
            { label: '행운의 숫자', value: result.lucky.number },
            { label: '좋은 방향', value: result.lucky.direction },
            { label: '지니면 좋은 것', value: result.lucky.item },
          ]
            .filter((item) => item.value)
            .map((item) => (
              <div key={item.label} className="border border-gray-200 rounded-lg p-3">
                <div className="text-xs text-gray-500">{item.label}</div>
                <div className="font-bold mt-0.5">{item.value}</div>
              </div>
            ))}
        </section>
      )}

      {/* 조언 */}
      {result.advice && (
        <section className="mb-6 rounded-xl border-2 border-hit p-4">
          <h2 className="font-bold mb-1 text-hit">오늘부터 이렇게</h2>
          <p className="text-gray-700 leading-relaxed whitespace-pre-line">{result.advice}</p>
        </section>
      )}

      {/* 공유 */}
      <div className="space-y-2">
        <SaveImageButton
          draw={() => drawSajuCard(reading)}
          fileName={`saju-${reading.sajuType.toLowerCase()}-${reading.readingId}.png`}
        />
        {shareUrl && <ShareButton url={shareUrl} label="🔗 결과 링크 복사" />}
        <Link
          href="/saju/compat"
          className="block w-full text-center font-medium py-2 px-4 rounded-lg border-2 border-gray-300 text-gray-700 hover:border-move hover:text-move transition"
        >
          💞 궁합도 보기
        </Link>
        <Link
          href="/saju"
          className="block w-full text-center font-medium py-2 px-4 rounded-lg border-2 border-gray-300 text-gray-700 hover:border-hit hover:text-hit transition"
        >
          다른 사주도 보기
        </Link>
      </div>

      <p className="mt-6 text-xs text-gray-400 text-center leading-relaxed">
        재미로 보는 콘텐츠입니다. 건강·투자·진로의 중요한 결정은 전문가와 상의하세요.
      </p>
    </main>
  );
}
