import type { Config } from 'tailwindcss';

const config: Config = {
  // 앱은 라이트 테마로 고정(globals.css의 color-scheme: light 참고).
  // 기본 'media' 전략이면 기기가 다크모드일 때 dark: 변형이 켜져
  // 흰 배경 위에 밝은 글자(slate-100 등)가 겹쳐 글자가 안 보이는 버그가
  // 생긴다. 'class' 전략으로 두면 어디에도 dark 클래스를 붙이지 않으므로
  // dark: 변형이 항상 무효 → 모든 브라우저/기기에서 라이트 테마로 렌더된다.
  darkMode: 'class',
  content: [
    './src/app/**/*.{ts,tsx}',
    './src/components/**/*.{ts,tsx}',
  ],
  theme: {
    extend: {
      colors: {
        hit: '#6aaa64',     // 초록 (Wordle 정확)
        move: '#c9b458',    // 노랑 (Wordle 자리만 다름)
        skip: '#787c7e',    // 회색 (Wordle 없음)
      },
    },
  },
  plugins: [],
};

export default config;
