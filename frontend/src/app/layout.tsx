import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'WordPlay',
  description: '친구들과 즐기는 한국어 단어 게임',
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
