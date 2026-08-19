// 팰월드 서버 설정(PalWorldSettings.ini) 읽기/쓰기
//
// 파일 형식은 대괄호 섹션 아래에 아주 긴 한 줄이 들어있는 구조다:
//   [/Script/Pal.PalGameWorldSettings]
//   OptionSettings=(Difficulty=None,DayTimeSpeedRate=1.000000,ServerName="...",...)
// 따옴표 안의 쉼표를 값 구분자로 착각하지 않도록 직접 파싱한다.

import fs from "fs";
import path from "path";

// 패널에서 편집할 설정 목록. type 에 따라 화면에서 체크박스/숫자/문자/선택으로 그려진다.
export const FIELDS = [
  // --- 서버 기본 ---
  { key: "ServerName", label: "서버 이름", type: "text", group: "서버 기본" },
  { key: "ServerDescription", label: "서버 설명", type: "text", group: "서버 기본" },
  { key: "ServerPassword", label: "접속 비밀번호 (비우면 공개)", type: "text", group: "서버 기본" },
  { key: "ServerPlayerMaxNum", label: "최대 접속 인원", type: "int", min: 1, max: 32, group: "서버 기본" },
  { key: "bIsPvP", label: "PvP 허용", type: "bool", group: "서버 기본" },
  { key: "AutoSaveSpan", label: "자동 저장 주기(초)", type: "float", group: "서버 기본" },

  // --- 사망 페널티 ---
  {
    key: "DeathPenalty", label: "사망 시 페널티", type: "select", group: "사망 페널티",
    options: [
      { value: "None", label: "아무것도 안 떨굼" },
      { value: "Item", label: "아이템만 떨굼" },
      { value: "ItemAndEquipment", label: "아이템 + 장비" },
      { value: "All", label: "전부 (아이템·장비·팰)" },
    ],
  },

  // --- 난이도/배율 ---
  { key: "ExpRate", label: "경험치 배율", type: "float", group: "난이도 · 배율" },
  { key: "PalCaptureRate", label: "팰 포획 확률 배율", type: "float", group: "난이도 · 배율" },
  { key: "WorkSpeedRate", label: "작업 속도 배율", type: "float", group: "난이도 · 배율" },
  { key: "CollectionDropRate", label: "채집량 배율", type: "float", group: "난이도 · 배율" },
  { key: "EnemyDropItemRate", label: "적 아이템 드랍 배율", type: "float", group: "난이도 · 배율" },
  { key: "PalEggDefaultHatchingTime", label: "알 부화 시간(시간, 0=즉시)", type: "float", group: "난이도 · 배율" },
  { key: "EggDefaultHatchingTime", label: "알 부화 시간(구버전 키)", type: "float", group: "난이도 · 배율" },

  // --- 소모 속도 ---
  { key: "PlayerStaminaDecreaceRate", label: "플레이어 스태미나 소모", type: "float", group: "소모 속도" },
  { key: "PlayerStomachDecreaceRate", label: "플레이어 배고픔 소모", type: "float", group: "소모 속도" },
  { key: "PalStaminaDecreaceRate", label: "팰 스태미나 소모", type: "float", group: "소모 속도" },
  { key: "PalStomachDecreaceRate", label: "팰 배고픔 소모", type: "float", group: "소모 속도" },

  // --- 거점 ---
  { key: "BaseCampMaxNumInGuild", label: "길드당 거점 최대 수", type: "int", group: "거점" },
  { key: "BaseCampWorkerMaxNum", label: "거점당 팰 최대 수", type: "int", group: "거점" },
  { key: "GuildPlayerMaxNum", label: "길드 최대 인원", type: "int", group: "거점" },
  { key: "BuildObjectDeteriorationDamageRate", label: "건물 노후 속도 (0=노후 없음)", type: "float", group: "거점" },
  { key: "BuildObjectHpRate", label: "건물 내구도 배율", type: "float", group: "거점" },
  { key: "DropItemMaxNum", label: "바닥 아이템 최대 수", type: "int", group: "거점" },
  // -1 이면 무제한. 떨어진 아이템 전부에 물리 연산이 걸려 CPU 를 크게 먹으므로 100 안팎을 권장.
  { key: "PhysicsActiveDropItemMaxNum", label: "물리 적용 아이템 수 (-1=무제한, CPU 영향 큼)", type: "int", group: "거점" },
  { key: "DropItemAliveMaxHours", label: "바닥 아이템 유지 시간", type: "float", group: "거점" },

  // --- 데미지 ---
  { key: "PlayerDamageRateAttack", label: "플레이어 공격력 배율", type: "float", group: "데미지" },
  { key: "PlayerDamageRateDefense", label: "플레이어 피해량 배율", type: "float", group: "데미지" },
  { key: "PalDamageRateAttack", label: "팰 공격력 배율", type: "float", group: "데미지" },
  { key: "PalDamageRateDefense", label: "팰 피해량 배율", type: "float", group: "데미지" },
  { key: "bEnablePlayerToPlayerDamage", label: "플레이어끼리 피해", type: "bool", group: "데미지" },
  { key: "bEnableFriendlyFire", label: "아군 오사(팰 포함)", type: "bool", group: "데미지" },

  // --- 시간/기타 ---
  { key: "DayTimeSpeedRate", label: "낮 흐름 속도", type: "float", group: "시간 · 기타" },
  { key: "NightTimeSpeedRate", label: "밤 흐름 속도", type: "float", group: "시간 · 기타" },
  { key: "bEnableInvaderEnemy", label: "습격 이벤트", type: "bool", group: "시간 · 기타" },
  { key: "bEnableAimAssistPad", label: "패드 에임 어시스트", type: "bool", group: "시간 · 기타" },
];

const FIELD_MAP = new Map(FIELDS.map((f) => [f.key, f]));

export function configPath(dir) {
  return path.join(dir, "PalWorldSettings.ini");
}

// OptionSettings 한 줄을 [키, 원본값] 배열로 분해 (순서 보존)
function splitPairs(inner) {
  const pairs = [];
  let cur = "";
  let inQuote = false;
  for (const ch of inner) {
    if (ch === '"') { inQuote = !inQuote; cur += ch; }
    else if (ch === "," && !inQuote) { pairs.push(cur); cur = ""; }
    else cur += ch;
  }
  if (cur.trim()) pairs.push(cur);
  return pairs
    .map((p) => {
      const i = p.indexOf("=");
      return i < 0 ? null : [p.slice(0, i).trim(), p.slice(i + 1).trim()];
    })
    .filter(Boolean);
}

export function readSettings(dir) {
  const file = configPath(dir);
  if (!fs.existsSync(file)) {
    throw new Error(`설정 파일을 찾을 수 없습니다: ${file} — PAL_CONFIG_DIR 설정을 확인하세요.`);
  }
  const text = fs.readFileSync(file, "utf8");
  const m = text.match(/OptionSettings=\((.*)\)/s);
  if (!m) {
    throw new Error("설정 파일이 비어 있거나 형식이 올바르지 않습니다. DefaultPalWorldSettings.ini 를 복사해 주세요.");
  }
  const pairs = splitPairs(m[1]);
  const values = {};
  for (const [k, raw] of pairs) {
    const f = FIELD_MAP.get(k);
    if (!f) continue;
    values[k] = decodeValue(f, raw);
  }
  return { values, text, pairs };
}

function decodeValue(field, raw) {
  if (field.type === "bool") return /^true$/i.test(raw);
  if (field.type === "int") return parseInt(raw, 10);
  if (field.type === "float") return parseFloat(raw);
  return raw.replace(/^"|"$/g, ""); // text, select
}

function encodeValue(field, val) {
  switch (field.type) {
    case "bool":
      return val ? "True" : "False";
    case "int":
      return String(Math.round(Number(val)));
    case "float":
      return Number(val).toFixed(6);
    case "select":
      // 열거형(DeathPenalty=None 등)은 따옴표 없이 그대로 써야 인식된다
      return String(val);
    default:
      // 문자열은 따옴표로 감싼다. 값 안의 따옴표는 줄 구조를 깨므로 제거
      return `"${String(val).replace(/"/g, "")}"`;
  }
}

// 들어온 값 검증 — 필드에 없는 키나 형식이 안 맞는 값은 거른다
export function validate(updates) {
  const clean = {};
  const errors = [];
  for (const [k, v] of Object.entries(updates || {})) {
    const f = FIELD_MAP.get(k);
    if (!f) continue; // 정의되지 않은 키는 무시(임의 설정 주입 방지)
    if (f.type === "bool") {
      clean[k] = !!v;
    } else if (f.type === "int" || f.type === "float") {
      const n = Number(v);
      if (!Number.isFinite(n)) { errors.push(`${f.label}: 숫자를 입력하세요.`); continue; }
      if (f.min != null && n < f.min) { errors.push(`${f.label}: ${f.min} 이상이어야 합니다.`); continue; }
      if (f.max != null && n > f.max) { errors.push(`${f.label}: ${f.max} 이하여야 합니다.`); continue; }
      clean[k] = n;
    } else if (f.type === "select") {
      const ok = (f.options || []).some((o) => o.value === v);
      if (!ok) { errors.push(`${f.label}: 허용되지 않은 값입니다.`); continue; }
      clean[k] = v;
    } else {
      clean[k] = String(v);
    }
  }
  return { clean, errors };
}

// 기존 줄의 순서와 편집 대상이 아닌 설정을 그대로 두고 값만 바꿔 쓴다
export function writeSettings(dir, updates) {
  const file = configPath(dir);
  const text = fs.readFileSync(file, "utf8");
  const m = text.match(/OptionSettings=\((.*)\)/s);
  if (!m) throw new Error("설정 파일 형식이 올바르지 않습니다.");

  const pairs = splitPairs(m[1]);
  const seen = new Set();
  const rebuilt = pairs.map(([k, raw]) => {
    if (Object.prototype.hasOwnProperty.call(updates, k)) {
      seen.add(k);
      return `${k}=${encodeValue(FIELD_MAP.get(k), updates[k])}`;
    }
    return `${k}=${raw}`;
  });
  // 파일에 없던 설정은 뒤에 덧붙인다
  for (const [k, v] of Object.entries(updates)) {
    if (seen.has(k)) continue;
    rebuilt.push(`${k}=${encodeValue(FIELD_MAP.get(k), v)}`);
  }

  const line = `OptionSettings=(${rebuilt.join(",")})`;
  const next = text.replace(/OptionSettings=\((.*)\)/s, () => line);

  // 저장 전 백업 (설정이 깨져도 되돌릴 수 있게)
  try { fs.copyFileSync(file, file + ".bak"); } catch {}
  fs.writeFileSync(file, next, "utf8");
  return rebuilt.length;
}
