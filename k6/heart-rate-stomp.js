import ws from 'k6/ws';
import { Counter, Rate, Trend } from 'k6/metrics';

const users = parseUsers(open(__ENV.USERS_FILE || './heart-rate-users.local.csv'));
const path = requiredChoice('HEART_PATH', ['single', 'batch'], 'single');
const route = requiredChoice('ROUTE', ['normal', 'emergency'], 'normal');
const warmupSeconds = numberEnv('WARMUP_SECONDS', 60);
const measureSeconds = numberEnv('MEASURE_SECONDS', 300);
const drainSeconds = numberEnv('DRAIN_SECONDS', 15);
const vus = numberEnv('VUS', 1);
const cadenceMillis = path === 'single' ? 1000 : 15000;
const bpm = numberEnv('BPM', route === 'normal' ? 70 : 180);
const summaryPath = __ENV.SUMMARY_PATH || 'heart-rate-summary.json';

const ackDuration = new Trend('heart_ws_ack_duration', true);
const sent = new Counter('heart_ws_sent');
const acked = new Counter('heart_ws_acked');
const measurementsSent = new Counter('heart_measurements_sent');
const measurementsAcked = new Counter('heart_measurements_acked');
const errors = new Rate('heart_ws_error');
const lost = new Counter('heart_ws_lost');
const duplicates = new Counter('heart_ws_duplicate');
const outOfOrder = new Counter('heart_ws_out_of_order');

export const options = {
  scenarios: {
    heart_rate: {
      executor: 'constant-vus',
      vus,
      duration: `${warmupSeconds + measureSeconds + drainSeconds + 1}s`,
      gracefulStop: '5s',
    },
  },
  summaryTrendStats: ['min', 'avg', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    heart_ws_lost: ['count==0'],
    heart_ws_duplicate: ['count==0'],
    heart_ws_out_of_order: ['count==0'],
  },
};

export default function () {
  if (__VU > users.length) {
    throw new Error(`VUS=${vus}인데 사용자 CSV에는 ${users.length}명만 있습니다.`);
  }

  const user = users[__VU - 1];
  const baseUrl = __ENV.WS_URL || 'ws://localhost:8080/ws/location';
  const socketUrl = sockJsUrl(baseUrl);
  const tags = { path, route };
  const pending = new Map();
  let connectedAt = 0;
  let lastAckAt = 0;
  let finished = false;

  const response = ws.connect(socketUrl, {
    headers: { Authorization: `Bearer ${user.accessToken}` },
    tags,
  }, (socket) => {
    socket.on('message', (message) => {
      for (const payload of decodeSockJs(message)) {
        if (payload.startsWith('CONNECTED')) {
          connectedAt = Date.now();
          subscribe(socket, '/user/queue/heart-rate/result', 'heart-result');
          subscribe(socket, '/user/queue/errors', 'heart-errors');
          sendHeartRate(socket, pending, connectedAt, tags);
          socket.setInterval(() => {
            if (!finished) {
              sendHeartRate(socket, pending, connectedAt, tags);
            }
          }, cadenceMillis);
          continue;
        }

        if (payload.startsWith('MESSAGE')) {
          handleMessage(payload, pending, tags, connectedAt, (measuredAt) => {
            if (lastAckAt !== 0 && measuredAt <= lastAckAt) {
              outOfOrder.add(1, tags);
            }
            lastAckAt = measuredAt;
          });
          continue;
        }

        if (payload.startsWith('ERROR')) {
          recordError(connectedAt, tags);
        }
      }

      if (message === 'o') {
        sendSockJs(socket, stompFrame('CONNECT', {
          'accept-version': '1.2',
          'heart-beat': '0,0',
          Authorization: `Bearer ${user.accessToken}`,
        }));
      }
    });

    socket.on('error', () => recordError(connectedAt, tags));
    socket.on('close', () => {
      if (!finished) {
        recordError(connectedAt, tags);
        recordPendingLoss(pending, tags);
      }
    });

    socket.setTimeout(() => {
      finished = true;
      if (connectedAt === 0) {
        errors.add(true, tags);
      }
      recordPendingLoss(pending, tags);
      socket.close();
    }, (warmupSeconds + measureSeconds + drainSeconds) * 1000);
  });

  if (response && response.status !== 101) {
    errors.add(true, tags);
  }
}

function sendHeartRate(socket, pending, connectedAt, tags) {
  const now = Date.now();
  const elapsed = now - connectedAt;
  if (elapsed >= (warmupSeconds + measureSeconds) * 1000) {
    return;
  }
  const measure = elapsed >= warmupSeconds * 1000;
  const request = buildRequest(now);
  const measuredAt = path === 'single'
    ? request.measuredAt
    : request.heartRates[request.heartRates.length - 1].measuredAt;

  const measurementCount = path === 'single' ? 1 : 15;
  pending.set(correlationKey(measuredAt), { sentAt: now, measure, measurementCount });
  if (measure) {
    sent.add(1, tags);
    measurementsSent.add(measurementCount, tags);
  }

  const destination = path === 'single'
    ? '/app/heart-rate/send-single'
    : '/app/heart-rate/send';
  sendSockJs(socket, stompFrame('SEND', {
    destination,
    'content-type': 'application/json',
  }, JSON.stringify(request)));
}

function buildRequest(now) {
  if (path === 'single') {
    return {
      heartRate: bpm,
      measuredAt: localDateTime(now),
      location: 'k6-load-test',
      context: 'UNKNOWN',
    };
  }

  const heartRates = [];
  for (let index = 14; index >= 0; index -= 1) {
    heartRates.push({
      heartRate: bpm,
      measuredAt: localDateTime(now - index * 1000),
    });
  }
  return {
    heartRates,
    location: 'k6-load-test',
    context: 'UNKNOWN',
  };
}

function handleMessage(frame, pending, tags, connectedAt, onAck) {
  const parsed = parseStompFrame(frame);
  if (parsed.headers.destination === '/user/queue/errors') {
    recordError(connectedAt, tags);
    return;
  }

  let body;
  try {
    body = JSON.parse(parsed.body);
  } catch (error) {
    recordError(connectedAt, tags);
    return;
  }

  const measuredAt = correlationKey(body.measuredAt);
  const request = pending.get(measuredAt);
  if (!request) {
    if (Date.now() - connectedAt >= warmupSeconds * 1000) {
      duplicates.add(1, tags);
      errors.add(true, tags);
    }
    return;
  }

  pending.delete(measuredAt);
  if (!request.measure) {
    return;
  }

  ackDuration.add(Date.now() - request.sentAt, tags);
  acked.add(1, tags);
  measurementsAcked.add(request.measurementCount, tags);
  errors.add(false, tags);
  onAck(measuredAt);
}

function recordPendingLoss(pending, tags) {
  for (const request of pending.values()) {
    if (request.measure) {
      lost.add(1, tags);
      errors.add(true, tags);
    }
  }
  pending.clear();
}

function subscribe(socket, destination, id) {
  sendSockJs(socket, stompFrame('SUBSCRIBE', { id, destination, ack: 'auto' }));
}

function recordError(connectedAt, tags) {
  if (connectedAt === 0 || Date.now() - connectedAt >= warmupSeconds * 1000) {
    errors.add(true, tags);
  }
}

function stompFrame(command, headers, body = '') {
  const lines = [command];
  for (const [key, value] of Object.entries(headers)) {
    lines.push(`${key}:${value}`);
  }
  lines.push('', body);
  return `${lines.join('\n')}\u0000`;
}

function sendSockJs(socket, frame) {
  socket.send(JSON.stringify([frame]));
}

function decodeSockJs(message) {
  if (message === 'o' || message === 'h') {
    return [];
  }
  if (!message.startsWith('a')) {
    return [];
  }
  return JSON.parse(message.substring(1));
}

function parseStompFrame(frame) {
  const separator = frame.indexOf('\n\n');
  const headerLines = frame.substring(0, separator).split('\n');
  const headers = {};
  for (const line of headerLines.slice(1)) {
    const colon = line.indexOf(':');
    if (colon > 0) {
      headers[line.substring(0, colon)] = line.substring(colon + 1);
    }
  }
  return {
    command: headerLines[0],
    headers,
    body: frame.substring(separator + 2).replace(/\u0000$/, ''),
  };
}

function sockJsUrl(baseUrl) {
  const server = String((__VU * 17) % 1000).padStart(3, '0');
  const session = `${__VU}-${__ITER}-${Date.now()}`.replace(/[^A-Za-z0-9_-]/g, '');
  return `${baseUrl}/${server}/${session}/websocket`;
}

function localDateTime(epochMillis) {
  return new Date(epochMillis).toISOString().replace('Z', '');
}

function correlationKey(measuredAt) {
  const value = Date.parse(`${measuredAt}Z`);
  if (!Number.isFinite(value)) {
    throw new Error(`ACK measuredAt 형식이 올바르지 않습니다: ${measuredAt}`);
  }
  return value;
}

function parseUsers(csv) {
  return csv.trim().split(/\r?\n/).slice(1).filter((line) => line.trim() !== '').map((line) => {
    const comma = line.indexOf(',');
    if (comma < 1) {
      throw new Error('사용자 CSV 형식은 member_id,access_token 이어야 합니다.');
    }
    return {
      memberId: line.substring(0, comma).trim(),
      accessToken: line.substring(comma + 1).trim(),
    };
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

function requiredChoice(name, choices, fallback) {
  const value = __ENV[name] || fallback;
  if (!choices.includes(value)) {
    throw new Error(`${name}은 ${choices.join(', ')} 중 하나여야 합니다.`);
  }
  return value;
}

export function handleSummary(data) {
  data.widyu = { path, route, warmupSeconds, measureSeconds, drainSeconds, vus };
  return {
    stdout: `${JSON.stringify(data.metrics, null, 2)}\n`,
    [summaryPath]: JSON.stringify(data, null, 2),
  };
}
