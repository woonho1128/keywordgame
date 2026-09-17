'use client';

import { PersonForm } from '@/lib/saju';

interface Props {
  label: string;
  value: PersonForm;
  onChange: (next: PersonForm) => void;
  namePlaceholder?: string;
  accent?: 'hit' | 'move';
}

/**
 * 생년월일시·성별 입력 묶음. 사주 한 명, 궁합 두 명에서 같이 쓴다.
 */
export function PersonFields({ label, value, onChange, namePlaceholder, accent = 'hit' }: Props) {
  const set = <K extends keyof PersonForm>(key: K, next: PersonForm[K]) =>
    onChange({ ...value, [key]: next });

  const focus = accent === 'move' ? 'focus:border-move' : 'focus:border-hit';
  const selected = accent === 'move' ? 'border-move bg-amber-50 text-move' : 'border-hit bg-green-50 text-hit';

  return (
    <fieldset className="border-2 border-gray-200 rounded-xl p-4">
      <legend className="px-2 text-sm font-bold">{label}</legend>

      <div className="space-y-3">
        <div>
          <label className="block text-xs text-gray-500 mb-1">이름 / 닉네임</label>
          <input
            type="text"
            value={value.nickname}
            onChange={(e) => set('nickname', e.target.value)}
            placeholder={namePlaceholder ?? '비워두면 기본 호칭으로 불러드려요'}
            maxLength={20}
            className={`w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none ${focus}`}
          />
        </div>

        <div>
          <label className="block text-xs text-gray-500 mb-1">생년월일 (양력) *</label>
          <input
            type="date"
            value={value.birthDate}
            onChange={(e) => set('birthDate', e.target.value)}
            min="1901-01-01"
            max={new Date().toISOString().slice(0, 10)}
            className={`w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none ${focus}`}
          />
        </div>

        <div>
          <label className="block text-xs text-gray-500 mb-1">태어난 시각 *</label>
          <input
            type="time"
            value={value.birthTime}
            onChange={(e) => set('birthTime', e.target.value)}
            disabled={value.timeUnknown}
            className={`w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none ${focus} disabled:bg-gray-100 disabled:text-gray-400`}
          />
          <label className="mt-2 flex items-center gap-2 text-sm text-gray-600">
            <input
              type="checkbox"
              checked={value.timeUnknown}
              onChange={(e) => set('timeUnknown', e.target.checked)}
              className="w-4 h-4"
            />
            시간을 몰라요 (시주 없이)
          </label>
        </div>

        <div>
          <label className="block text-xs text-gray-500 mb-1">성별 *</label>
          <div className="grid grid-cols-2 gap-2">
            {(['MALE', 'FEMALE'] as const).map((option) => (
              <button
                key={option}
                type="button"
                onClick={() => set('gender', option)}
                className={`border-2 rounded-lg py-2 text-sm font-medium transition ${
                  value.gender === option
                    ? selected
                    : 'border-gray-200 text-gray-500 hover:border-gray-300'
                }`}
              >
                {option === 'MALE' ? '남성' : '여성'}
              </button>
            ))}
          </div>
        </div>
      </div>
    </fieldset>
  );
}
