// 스파이폴(Spyfall) 장소 & 역할 데이터.
// 맵별 최대 12개 역할(= 최대 12인 게임 대응).
// 이미지로 제공된 역할을 기반으로, 부족분은 스파이폴 표준/합리적 역할로 채웠다.

export type SpyfallLocation = {
  name: string; // 한국어 장소명
  en: string; // 영어 장소명
  roles: string[]; // 한국어 역할 목록 (최대 12)
};

export const LOCATIONS: SpyfallLocation[] = [
  {
    name: '비행기',
    en: 'Airplane',
    roles: ['기장', '부조종사', '승무원', '사무장', '일등석 승객', '비즈니스 클래스 손님', '이코노미석 승객', '항공 보안관', '정비공', '기관사', '밀입국자', '어린이 승객'],
  },
  {
    name: '놀이공원',
    en: 'Amusement Park',
    roles: ['손님', '노점상', '정비공', '어린이', '광대', '관광객', '놀이기구 조작원', '매표원', '마스코트 인형탈', '청소부', '안전요원', '사진사'],
  },
  {
    name: '은행',
    en: 'Bank',
    roles: ['상담원', '도둑', '고객', '지점장', '무장한 운전자', '창구 직원', '경비원', '대출 담당자', '인턴', '청원경찰', 'VIP 고객', '청소부'],
  },
  {
    name: '해변',
    en: 'Beach',
    roles: ['안전 요원', '패러글라이더', '길거리 음식 노점상', '원숭이랑 같이 있는 사진작가', '오락 감독', '피서객', '도둑', '서퍼', '아이스크림 장수', '비치발리볼 선수', '커플', '조개 줍는 사람'],
  },
  {
    name: '카니발 축제',
    en: 'Carnival',
    roles: ['재연배우', '관광객', '사진 작가', '기자', '의상 담당자', '롤플레잉 게임 팬', '노점상', '퍼레이드 무용수', '진행요원', '음악 밴드', '어린이', '마술사'],
  },
  {
    name: '카지노',
    en: 'Casino',
    roles: ['딜러', '타짜', '보안 책임자', '입구 지키미', '도박꾼', '매니저', '바텐더', '웨이트리스', 'VIP 손님', '슬롯머신 정비사', '사기꾼', '초보 손님'],
  },
  {
    name: '서커스 텐트',
    en: 'Circus Tent',
    roles: ['곡예사', '광대', '칼 던지는 사람', '저글러', '조련사', '서커스 단골', '마술사', '외줄타기 곡예사', '차력사', '단장', '매표원', '관객'],
  },
  {
    name: '회식',
    en: 'Corporate Party',
    roles: ['CEO', '비서실장', '경리', '배달원', '매니저', '사회자', '불청객', '신입사원', '부장', '인턴', '거래처 손님', '케이터링 직원'],
  },
  {
    name: '십자군 군대',
    en: 'Crusader Army',
    roles: ['붙잡힌 사라센인', '하인', '수도승', '궁수', '대지주', '주교', '기사', '창병', '대장장이', '종군 의사', '척후병', '왕'],
  },
  {
    name: '온천 스파',
    en: 'Day Spa',
    roles: ['스타일리스트', '손님', '피부과 전문의', '피부 미용사', '마사지사', '네일 아티스트', '메이크업 스페셜리스트', '접수원', '요가 강사', '사우나 관리인', '청소부', '단골 손님'],
  },
  {
    name: '대사관',
    en: 'Embassy',
    roles: ['대사', '외교관', '관료', '비서', '경비원', '난민', '관광객', '통역사', '영사', '방문 기자', '비자 신청자', '청원경찰'],
  },
  {
    name: '병원',
    en: 'Hospital',
    roles: ['내과 과장', '내과 의사', '외과 의사', '간호사', '환자', '인턴', '병리학자', '응급실 의사', '마취과 의사', '보호자', '원무과 직원', '구급대원'],
  },
  {
    name: '호텔',
    en: 'Hotel',
    roles: ['호텔 매니저', '문지기', '경비원', '객실 청소원', '손님', '바텐더', '접수 담당자', '벨보이', '셰프', '컨시어지', '주차 요원', 'VIP 투숙객'],
  },
  {
    name: '군부대',
    en: 'Military Base',
    roles: ['부사관', '이등병', '초병', '의무병', 'PX병', '탈영병', '대령', '소대장', '취사병', '행정병', '운전병', '헌병'],
  },
  {
    name: '영화 촬영장',
    en: 'Movie Studio',
    roles: ['감독', '사운드 엔지니어', '카메라맨', '스턴트맨', '의상 디자이너', '배우', '엑스트라', '조명 기사', '분장사', '제작자', '시나리오 작가', '소품 담당'],
  },
  {
    name: '나이트클럽',
    en: 'Nightclub',
    roles: ['입구 지키미', 'DJ', '단골', '모델', '댄서', '픽업아티스트', '바텐더', '웨이터', '사장', '취객', '경비원', '화장실 도우미'],
  },
  {
    name: '원양 정기선',
    en: 'Ocean Liner',
    roles: ['선장', '요리사', '뮤지션', '바텐더', '무선 통신사', '돈 많은 승객', '종업원', '항해사', '기관사', '갑판원', '승무원', '밀항자'],
  },
  {
    name: '여객 열차',
    en: 'Passenger Train',
    roles: ['정비공', '국경 순찰대', '승무원', '승객', '식당칸 요리사', '기관사', '화부', '검표원', '무임승차자', '카트 판매원', '짐꾼', '청소부'],
  },
  {
    name: '해적선',
    en: 'Pirate Ship',
    roles: ['요리사', '해방된 죄수', '노예', '허세부리는 선장', '선원', '사환', '포수', '항해사', '갑판장', '앵무새 조련사', '인질', '보물 관리인'],
  },
  {
    name: '극지 탐험 기지',
    en: 'Polar Station',
    roles: ['지구과학자', '탐험대장', '무선 통신사', '수문학자', '기상학자', '생물학자', '의사', '요리사', '정비공', '지질학자', '다큐 촬영기사', '개썰매꾼'],
  },
  {
    name: '경찰서',
    en: 'Police Station',
    roles: ['기자', '부서장', '동네 경찰', '탐정', '변호사', '형사 전문 변호사', '용의자', '순경', '유치장 죄수', '목격자', '감식반', '민원인'],
  },
  {
    name: '식당',
    en: 'Restaurant',
    roles: ['지배인', '그릇 치우는 직원', '뮤지션', '손님', '웨이터', '음식 비평가', '쉐프', '주방 보조', '계산원', '배달원', '단골 손님', '설거지 담당'],
  },
  {
    name: '학교',
    en: 'School',
    roles: ['교장 선생님', '교감 선생님', '수학 선생님', '학생', '체육 선생님', '경비원', '환경 미화원', '급식 조리사', '학부모', '보건 선생님', '행정실 직원', '전학생'],
  },
  {
    name: '휴게소',
    en: 'Service Station',
    roles: ['지배인', '타이어 전문가', '오토바이족', '차 주인', '세차원', '전기 기사', '자동차 정비공', '주유원', '편의점 직원', '식당 직원', '화장실 청소부', '트럭 운전사'],
  },
  {
    name: '우주 정거장',
    en: 'Space Station',
    roles: ['우주선 선장', '조종사', '기계 기사', '연구원', '우주 여행객', '의사', '외계인', '통신 담당', '생명유지 기술자', '우주 비행사', '관제사', '로봇 정비사'],
  },
  {
    name: '잠수함',
    en: 'Submarine',
    roles: ['선원', '전기팀장', '요리사', '무전병', '수중 음파 탐지병', '항해사', '지휘관', '어뢰 담당', '잠망경 관측병', '기관병', '군의관', '신참 승조원'],
  },
  {
    name: '마트',
    en: 'Supermarket',
    roles: ['손님', '계산원', '정육점 직원', '환경 미화원', '경비원', '시식 코너 직원', '진열 담당자', '매장 매니저', '카트 정리원', '수산 코너 직원', '도둑', '배달 기사'],
  },
  {
    name: '극장',
    en: 'Theater',
    roles: ['감독', '무대 담당자', '배우', '프롬프터', '좌석 안내원', '청중', '물품 보관소 안내원', '매표원', '조명 담당', '분장사', '극작가', '매점 직원'],
  },
  {
    name: '대학',
    en: 'University',
    roles: ['총장', '학장', '교수', '대학원생', '대학생', '캠퍼스 경비', '연구원', '조교', '교환학생', '도서관 사서', '청소 노동자', '조교수'],
  },
  {
    name: '동물원',
    en: 'Zoo',
    roles: ['손님', '수의사', '사육사', '관람 가이드', '노점상', '환경 미화원', '지배인', '매표원', '어린이 관람객', '사진작가', '조련사', '청소부'],
  },
  {
    name: '보드 게임 가게',
    en: 'Board Game Store',
    roles: ['가게 주인', '모르는 게 없는 손님', '처음 온 사람', '게임 디자이너', '시급 제대로 못받는 알바생', '노숙자', '전쟁 게임 마니아', 'RPG 게임 마니아', '리빙카드게임 마니아', '초등학생 손님', '대회 참가자', '부품 파는 직원'],
  },
  {
    name: '공동묘지',
    en: 'Cemetery',
    roles: ['귀신', '도굴범', '관리인', '플로리스트', '슬픈 과부', '행복한 과부', '묘비 조각가', '장의사', '무덤 파는 사람', '성직자', '조문객', '유령 사냥꾼'],
  },
  {
    name: '카페',
    en: 'Coffee Shop',
    roles: ['바리스타', '손님', '재즈 연주가', '작가가 되고 싶은 사람', '버림받은 애인', '가정주부', '가게 매니저', '노숙자', '권력 있는 사업가', '단골 손님', '대학생', '알바생'],
  },
  {
    name: '어린이집',
    en: 'Daycare Center',
    roles: ['시간에 쫓기는 엄마', '어린이집 선생님', '돌보미', '아이', '전업 남편', '어린이집 원장', '조리사', '다정한 엄마', '할머니', '학부모회장', '통학차 기사', '보조 교사'],
  },
];

export type PlayerRole = {
  player: number; // 1-based 플레이어 번호
  isSpy: boolean;
  location: string | null; // 스파이는 null
  role: string | null; // 스파이는 null
};

export type SpyfallGame = {
  location: SpyfallLocation;
  players: PlayerRole[];
  spyCount: number;
};

function shuffle<T>(arr: T[]): T[] {
  const a = [...arr];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

/**
 * 스파이폴 게임 1판을 배정한다.
 * - 랜덤 장소 1곳 선택
 * - 랜덤으로 spyCount명을 스파이로 지정
 * - 나머지 플레이어에게 그 장소의 역할을 (겹치지 않게) 배정
 */
export function assignGame(playerCount: number, spyCount: number): SpyfallGame {
  const location = LOCATIONS[Math.floor(Math.random() * LOCATIONS.length)];

  // 스파이가 될 플레이어 번호 선택
  const order = shuffle(Array.from({ length: playerCount }, (_, i) => i + 1));
  const spies = new Set(order.slice(0, spyCount));

  // 역할 풀: 부족하면 순환해서 채운다(안전장치).
  const nonSpyCount = playerCount - spyCount;
  const rolePool = shuffle(location.roles);
  const assignedRoles: string[] = [];
  for (let i = 0; i < nonSpyCount; i++) {
    assignedRoles.push(rolePool[i % rolePool.length]);
  }
  const shuffledRoles = shuffle(assignedRoles);

  let roleIdx = 0;
  const players: PlayerRole[] = Array.from({ length: playerCount }, (_, i) => {
    const num = i + 1;
    if (spies.has(num)) {
      return { player: num, isSpy: true, location: null, role: null };
    }
    return {
      player: num,
      isSpy: false,
      location: location.name,
      role: shuffledRoles[roleIdx++],
    };
  });

  return { location, players, spyCount };
}
