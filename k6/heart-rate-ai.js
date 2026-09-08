import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const users = parseUsers(open(__ENV.USERS_FILE || './heart-rate-users.local.csv'));
const path = choice('HEART_PATH', ['single', 'batch'], 'single');
const route = choice('ROUTE', ['normal', 'emergency'], 'normal');
const warmupSeconds = numberEnv('WARMUP_SECONDS', 60);
const measureSeconds = numberEnv('MEASURE_SECONDS', 300);
const vus = numberEnv('VUS', 1);
const bpm = numberEnv('BPM', route === 'normal' ? 70 : 180);
const summaryPath = __ENV.SUMMARY_PATH || 'heart-rate-ai-summary.json';

const duration = new Trend('heart_ai_direct_duration', true);
const requests = new Counter('heart_ai_direct_requests');
const errors = new Rate('heart_ai_direct_error');

export const options = {
  scenarios: {
    heart_rate_ai: {
      executor: 'constant-vus',
      vus,
      duration: `${warmupSeconds + measureSeconds}s`,
      gracefulStop: '5s',
    },
  },
  summaryTrendStats: ['min', 'avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function (data) {
  if (__VU > users.length) {
    throw new Error(`VUS=${vus}인데 사용자 CSV에는 ${users.length}명만 있습니다.`);
  }

  const user = users[__VU - 1];
  const startedAt = Date.now();
  const measurement = startedAt - data.testStartedAt >= warmupSeconds * 1000;
  const repeat = path === 'batch' ? 15 : 1;
  const aiUrl = `${__ENV.AI_URL || 'http://localhost:5000'}/api/hr`;
  const tags = { path, route };

  for (let index = 0; index < repeat; index += 1) {
    const requestStartedAt = Date.now();
    const response = http.post(aiUrl, JSON.stringify({
      user_id: String(user.memberId),
      bpm,
      context: 'UNKNOWN',
      timestamp: requestStartedAt / 1000,
    }), {
      headers: { 'Content-Type': 'application/json' },
      tags,
    });

    if (measurement) {
      duration.add(Date.now() - requestStartedAt, tags);
      requests.add(1, tags);
      errors.add(response.status !== 200, tags);
    }
  }

  sleep(path === 'batch' ? 15 : 1);
}

export function setup() {
  return { testStartedAt: Date.now() };
}

function parseUsers(csv) {
  return csv.trim().split(/\r?\n/).slice(1).filter((line) => line.trim() !== '').map((line) => {
    const comma = line.indexOf(',');
    return { memberId: line.substring(0, comma).trim() };
  });
}

function numberEnv(name, fallback) {
  const value = __ENV[name];
  if (value === undefined || value === '') {
    return fallback;
  }
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed <= 0) {
    throw new Error(`${name}은 양수여야 합니다.`);
  }
  return parsed;
}

function choice(name, choices, fallback) {
  const value = __ENV[name] || fallback;
  if (!choices.includes(value)) {
    throw new Error(`${name}은 ${choices.join(', ')} 중 하나여야 합니다.`);
  }
  return value;
}

export function handleSummary(data) {
  data.widyu = { path, route, warmupSeconds, measureSeconds, vus };
  return {
    stdout: `${JSON.stringify(data.metrics, null, 2)}\n`,
    [summaryPath]: JSON.stringify(data, null, 2),
  };
}
