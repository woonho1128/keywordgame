import type { Config } from 'tailwindcss';

const config: Config = {
  // hover: 스타일을 마우스가 있는 기기에서만 적용 — 터치 후 hover가 남아 버튼 색이 바뀌어 보이는 현상 방지
  future: {
    hoverOnlyWhenSupported: true,
  },
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
