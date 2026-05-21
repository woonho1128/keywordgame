import type { Config } from 'tailwindcss';

const config: Config = {
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
