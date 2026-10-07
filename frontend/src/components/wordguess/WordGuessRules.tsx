import { JamoTile } from './JamoTile';

interface Props {
  maxAttempts: number;
  defaultOpen?: boolean;
}

/** 분해 예시 한 개 — 좁은 화면에서 예시 중간이 아니라 예시 사이에서 줄바꿈되게 묶는다. */
function Ex({ from, to }: { from: string; to: string }) {
  return <span className="whitespace-nowrap">{from} → {to}</span>;
}

/**
 * WordGuess 게임 방법 — 접고 펼 수 있는 안내 박스.
 * 판정 규칙은 백엔드 HangulUtil.compareWords 와 맞춰야 한다.
 */
export function WordGuessRules({ maxAttempts, defaultOpen = false }: Props) {
  return (
    <details open={defaultOpen} className="rounded-lg border border-gray-200 bg-white px-4 py-3 text-sm text-gray-700">
      <summary className="cursor-pointer select-none font-bold text-gray-800">게임 방법</summary>
      <div className="mt-3 space-y-3">
        <ul className="list-disc pl-5 space-y-1">
          <li>정답 단어를 <b>{maxAttempts}번</b> 안에 맞히세요.</li>
          <li>추측은 정답과 <b>글자 수</b>, <b>자모 수</b>가 모두 같아야 해요.</li>
          <li>이미 낸 단어는 다시 낼 수 없어요.</li>
        </ul>
        <div>
          <p className="font-medium mb-1">자모는 기본 글자로 쪼개서 셉니다</p>
          <ul className="list-disc pl-5 space-y-0.5 text-gray-600">
            <li>쌍자음: <Ex from="ㄲ" to="ㄱ ㄱ" />, <Ex from="ㅆ" to="ㅅ ㅅ" /></li>
            <li>이중모음: <Ex from="ㅐ" to="ㅏ ㅣ" />, <Ex from="ㅘ" to="ㅗ ㅏ" />, <Ex from="ㅙ" to="ㅗ ㅏ ㅣ" /></li>
            <li>겹받침: <Ex from="ㄺ" to="ㄹ ㄱ" />, <Ex from="ㅄ" to="ㅂ ㅅ" /></li>
            <li>예) <Ex from="사과" to="ㅅ ㅏ ㄱ ㅗ ㅏ (5개)" /></li>
          </ul>
        </div>
        <div className="space-y-1.5">
          <div className="flex items-center gap-2">
            <JamoTile jamo="ㄱ" mark="H" />
            <span>그 자리에 맞는 자모 (초성·받침 위치까지 일치)</span>
          </div>
          <div className="flex items-center gap-2">
            <JamoTile jamo="ㄱ" mark="M" />
            <span>정답에 있지만 다른 자리</span>
          </div>
          <div className="flex items-center gap-2">
            <JamoTile jamo="ㄱ" mark="S" />
            <span>정답에 없음</span>
          </div>
        </div>
      </div>
    </details>
  );
}
