'use client';

import { ELEMENT_ORDER, SajuChart, elementStyle } from '@/lib/saju';

/**
 * 계산된 사주팔자 표시.
 * AI가 만든 글이 아니라 서버가 계산한 "사실"이라서 해석과 시각적으로 구분한다.
 */
export function SajuChartView({ chart, title = '사주팔자' }: { chart: SajuChart; title?: string }) {
  const maxCount = Math.max(1, ...Object.values(chart.elementCounts));
  const tenGodGroups = chart.tenGodGroups ?? {};

  return (
    <section className="border-2 border-gray-200 rounded-xl p-4 sm:p-5">
      <div className="flex items-baseline justify-between mb-3">
        <h2 className="font-bold">{title}</h2>
        <span className="text-xs text-gray-400">서버 계산 · AI 추측 아님</span>
      </div>

      {/* 기둥 4개 (시간 모르면 3개) */}
      <div className={chart.hourKnown ? 'grid grid-cols-4 gap-1.5 sm:gap-2' : 'grid grid-cols-3 gap-1.5 sm:gap-2'}>
        {chart.pillars.map((pillar) => {
          const stem = elementStyle(pillar.stemElement);
          const branch = elementStyle(pillar.branchElement);
          return (
            <div key={pillar.position} className="text-center">
              <div className="text-xs text-gray-500 mb-1">{pillar.position}</div>

              <div className={`border rounded-t-lg py-2 ${stem.chip}`}>
                <div className="text-2xl font-bold leading-tight">{pillar.stemHanja}</div>
                <div className="text-xs">
                  {pillar.stem} · {pillar.stemElement}
                </div>
              </div>
              <div className={`border border-t-0 rounded-b-lg py-2 ${branch.chip}`}>
                <div className="text-2xl font-bold leading-tight">{pillar.branchHanja}</div>
                <div className="text-xs">
                  {pillar.branch} · {pillar.branchElement}
                </div>
              </div>

              <div className="mt-1 text-[11px] text-gray-500 leading-tight">
                {pillar.stemTenGod}
                <br />
                {pillar.branchTenGod}
                {pillar.hiddenStems && pillar.hiddenStems.length > 0 && (
                  <>
                    <br />
                    <span className="text-gray-400">{pillar.hiddenStems.join('')}</span>
                  </>
                )}
              </div>
            </div>
          );
        })}
      </div>

      {!chart.hourKnown && (
        <p className="mt-3 text-xs text-gray-500">
          태어난 시각을 몰라 시주는 빼고 여섯 글자로 풀었습니다.
        </p>
      )}

      {/* 오행 분포 */}
      <div className="mt-5">
        <div className="flex items-baseline justify-between mb-2">
          <h3 className="text-sm font-bold">오행 분포</h3>
          <span className="text-xs text-gray-500">
            일간 {chart.dayMaster}({chart.dayMasterHanja}) · {chart.zodiac}띠 · 세는나이{' '}
            {chart.koreanAge}세
          </span>
        </div>
        <div className="space-y-1.5">
          {ELEMENT_ORDER.map((element) => {
            const count = chart.elementCounts[element] ?? 0;
            const style = elementStyle(element);
            return (
              <div key={element} className="flex items-center gap-2">
                <span className={`w-5 text-sm font-bold ${style.text}`}>{element}</span>
                <div className="flex-1 h-3 bg-gray-100 rounded-full overflow-hidden">
                  <div
                    className={`h-full rounded-full ${style.bar}`}
                    style={{ width: `${(count / maxCount) * 100}%` }}
                  />
                </div>
                <span className="w-4 text-xs text-gray-500 text-right">{count}</span>
              </div>
            );
          })}
        </div>
        {chart.missingElements.length > 0 && (
          <p className="mt-2 text-xs text-gray-500">
            없는 오행: {chart.missingElements.join(', ')}
          </p>
        )}
      </div>

      {/* 십성 분포 — 해석의 핵심 근거 */}
      {Object.keys(tenGodGroups).length > 0 && (
        <div className="mt-5">
          <div className="flex items-baseline justify-between mb-2">
            <h3 className="text-sm font-bold">십성 분포</h3>
            {chart.bodyStrength && (
              <span className="text-xs text-gray-500">
                일간의 힘 {chart.bodyStrength} · {chart.monthSupport ? '득령' : '실령'}
              </span>
            )}
          </div>
          <div className="grid grid-cols-5 gap-1.5">
            {Object.entries(tenGodGroups).map(([group, count]) => (
              <div
                key={group}
                className={`rounded-lg border py-1.5 text-center ${
                  count === 0 ? 'border-gray-200 text-gray-300' : 'border-gray-300 text-gray-700'
                }`}
              >
                <div className="text-[11px]">{group}</div>
                <div className="font-bold text-sm">{count}</div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* 대운 */}
      <div className="mt-5">
        <div className="flex items-baseline justify-between mb-2">
          <h3 className="text-sm font-bold">대운 ({chart.forwardLuck ? '순행' : '역행'})</h3>
          <span className="text-xs text-gray-500">
            {chart.yearlyLuckYear}년 세운 {chart.yearlyLuck}
          </span>
        </div>
        <div className="flex gap-1.5 overflow-x-auto pb-1">
          {chart.luckCycles.map((cycle) => (
            <div
              key={cycle.order}
              className={`shrink-0 text-center px-2 py-1.5 rounded-lg border text-xs ${
                cycle.current
                  ? 'border-hit bg-hit text-white font-bold'
                  : 'border-gray-200 text-gray-600'
              }`}
            >
              <div>{cycle.startAge}세</div>
              <div className="font-medium">{cycle.pillar}</div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
