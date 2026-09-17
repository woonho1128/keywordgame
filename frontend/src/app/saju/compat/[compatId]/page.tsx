'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { api } from '@/lib/api';
import { CompatReading } from '@/lib/saju';
import { SajuChartView } from '@/components/saju/SajuChartView';
import { SaveImageButton } from '@/components/saju/SaveImageButton';
import { ShareButton } from '@/components/common/ShareButton';
import { drawCompatCard } from '@/lib/sajuCard';

/** 점수대별 한 줄 라벨 — 숫자만 보면 감이 안 오니까 */
function scoreLabel(score: number) {
  if (score >= 85) return '아주 잘 맞는 조합';
  if (score >= 72) return '잘 맞는 편';
  if (score >= 58) return '무난한 사이';
  if (score >= 45) return '노력이 필요한 사이';
  return '많이 다른 두 사람';
}

export default function CompatResultPage({ params }: { params: { compatId: string } }) {
  const [reading, setReading] = useState<CompatReading | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [shareUrl, setShareUrl] = useState('');
  const [showCharts, setShowCharts] = useState(false);

  useEffect(() => {
    setShareUrl(window.location.href);
    api<CompatReading>(`/api/v1/saju/compat/${params.compatId}`)
      .then(setReading)
      .catch((err) => setError(err instanceof Error ? err.message : '결과를 불러오지 못했습니다.'));
  }, [params.compatId]);

  if (error) {
    return (
      <main className="min-h-screen p-8 max-w-xl mx-auto">
        <p className="text-red-500">{error}</p>
        <Link href="/saju/compat" className="text-sm text-gray-500 underline mt-4 inline-block">
          궁합 다시 보기
        </Link>
      </main>
    );
  }

  if (!reading) {
    return (
      <main className="min-h-screen p-8 max-w-xl mx-auto">
        <p className="text-gray-400">두 사주를 맞춰보는 중...</p>
      </main>
    );
  }

  const { analysis, result } = reading;

  return (
    <main className="min-h-screen p-5 sm:p-8 max-w-xl mx-auto">
      <Link href="/saju/compat" className="text-sm text-gray-400 hover:text-gray-600">
        ← 다른 궁합 보기
      </Link>

      <header className="mt-3 mb-6">
        <div className="text-sm text-gray-500">
          {reading.typeEmoji} {reading.typeLabel}
        </div>
        <h1 className="text-2xl sm:text-3xl font-bold mt-1 leading-snug">
          {result.headline || reading.typeLabel}
        </h1>
        <p className="text-sm text-gray-500 mt-2">
          {analysis.aName} 💞 {analysis.bName}
        </p>
      </header>

      {/* 점수 */}
      <section className="mb-6">
        <div className="flex items-baseline justify-between mb-1">
          <span className="text-sm font-medium text-gray-600">{scoreLabel(analysis.score)}</span>
          <span className="text-2xl font-bold">{analysis.score}점</span>
        </div>
        <div className="h-3 bg-gray-100 rounded-full overflow-hidden">
          <div
            className="h-full bg-move rounded-full transition-all"
            style={{ width: `${Math.min(100, Math.max(0, analysis.score))}%` }}
          />
        </div>
      </section>

      <section className="mb-6 rounded-xl bg-gray-50 p-4 sm:p-5">
        <p className="leading-relaxed whitespace-pre-line">{result.summary}</p>
      </section>

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

      {/* 계산된 관계 근거 */}
      <section className="mb-6 border-2 border-gray-200 rounded-xl p-4 sm:p-5">
        <div className="flex items-baseline justify-between mb-3">
          <h2 className="font-bold">두 사주의 관계</h2>
          <span className="text-xs text-gray-400">서버 계산 · AI 추측 아님</span>
        </div>

        <div className="grid grid-cols-2 gap-2 mb-3 text-sm">
          <div className="rounded-lg border border-gray-200 p-3">
            <div className="text-xs text-gray-500">{analysis.aName}이(가) 보는 상대</div>
            <div className="font-bold mt-0.5">{analysis.aSeesB}</div>
          </div>
          <div className="rounded-lg border border-gray-200 p-3">
            <div className="text-xs text-gray-500">{analysis.bName}이(가) 보는 상대</div>
            <div className="font-bold mt-0.5">{analysis.bSeesA}</div>
          </div>
        </div>

        {analysis.dayStemHap && (
          <p className="mb-3 text-sm text-hit font-medium">
            ✦ 두 일간이 천간합({analysis.dayStemHap})을 이룹니다 — 서로 끌어당기는 관계
          </p>
        )}

        <ul className="space-y-1.5">
          {analysis.signals.map((signal, index) => (
            <li key={index} className="flex gap-2 text-sm">
              <span
                className={`shrink-0 rounded px-1.5 text-xs font-bold self-start mt-0.5 ${
                  signal.positive ? 'bg-hit/10 text-hit' : 'bg-move/15 text-move'
                }`}
              >
                {signal.position}
              </span>
              <span className="text-gray-700 leading-relaxed">{signal.detail}</span>
            </li>
          ))}
          {analysis.signals.length === 0 && (
            <li className="text-sm text-gray-500">
              눈에 띄는 합도 충도 없는 조합입니다. 서로 간섭이 적은 편이에요.
            </li>
          )}
        </ul>

        <button
          onClick={() => setShowCharts((v) => !v)}
          className="mt-4 w-full text-sm text-gray-500 hover:text-gray-700 border-t border-gray-100 pt-3"
        >
          {showCharts ? '사주팔자 접기 ▲' : '두 사람의 사주팔자 펼치기 ▼'}
        </button>
        {showCharts && (
          <div className="mt-3 space-y-3">
            <SajuChartView chart={analysis.aChart} title={`${analysis.aName}의 사주팔자`} />
            <SajuChartView chart={analysis.bChart} title={`${analysis.bName}의 사주팔자`} />
          </div>
        )}
      </section>

      {/* 해석 섹션 */}
      <section className="space-y-4 mb-6">
        {result.sections.map((section, index) => (
          <article key={index} className="border-l-4 border-move pl-4">
            <h2 className="font-bold mb-1">{section.title}</h2>
            <p className="text-gray-700 leading-relaxed whitespace-pre-line">{section.body}</p>
          </article>
        ))}
      </section>

      {/* 좋은 점 / 부딪치는 점 */}
      {((result.strengths?.length ?? 0) > 0 || (result.cautions?.length ?? 0) > 0) && (
        <section className="mb-6 grid grid-cols-1 sm:grid-cols-2 gap-3">
          {result.strengths && result.strengths.length > 0 && (
            <div className="rounded-xl border-2 border-hit/40 bg-green-50/50 p-4">
              <h2 className="font-bold text-hit mb-2">잘 맞는 부분</h2>
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
              <h2 className="font-bold text-move mb-2">부딪칠 수 있는 부분</h2>
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

      {/* 서로에게 해주면 좋은 것 */}
      {(!!result.aToB || !!result.bToA) && (
        <section className="mb-6 space-y-3">
          {result.aToB && (
            <div className="rounded-xl border border-gray-200 p-4">
              <h2 className="font-bold text-sm mb-1">
                {analysis.aName} → {analysis.bName}
              </h2>
              <p className="text-gray-700 leading-relaxed">{result.aToB}</p>
            </div>
          )}
          {result.bToA && (
            <div className="rounded-xl border border-gray-200 p-4">
              <h2 className="font-bold text-sm mb-1">
                {analysis.bName} → {analysis.aName}
              </h2>
              <p className="text-gray-700 leading-relaxed">{result.bToA}</p>
            </div>
          )}
        </section>
      )}

      {result.advice && (
        <section className="mb-6 rounded-xl border-2 border-move p-4">
          <h2 className="font-bold mb-1 text-move">오래 잘 지내려면</h2>
          <p className="text-gray-700 leading-relaxed whitespace-pre-line">{result.advice}</p>
        </section>
      )}

      <div className="space-y-2">
        <SaveImageButton
          draw={() => drawCompatCard(reading)}
          fileName={`gunghap-${reading.compatType.toLowerCase()}-${reading.compatId}.png`}
        />
        {shareUrl && <ShareButton url={shareUrl} label="🔗 결과 링크 복사" />}
        <Link
          href="/saju"
          className="block w-full text-center font-medium py-2 px-4 rounded-lg border-2 border-gray-300 text-gray-700 hover:border-hit hover:text-hit transition"
        >
          🔮 내 사주도 보기
        </Link>
      </div>

      <p className="mt-6 text-xs text-gray-400 text-center leading-relaxed">
        재미로 보는 콘텐츠입니다. 점수가 낮아도 맞춰갈 수 있고, 높아도 노력은 필요해요.
      </p>
    </main>
  );
}
