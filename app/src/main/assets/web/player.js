// 플레이어 페이지: 서버 큐(WebSocket)를 받아 YouTube IFrame Player로 재생한다.
// 반드시 호스트 폰의 크롬에서 열어야 프리미엄(광고 없음)이 적용된다.
// 호스트 앱이 /player?host=<토큰> 으로 연다. 토큰이 있어야 재생 종료·진행 상황을 서버에 알릴 수 있다.

let player = null;
let playerReady = false;
let started = false;        // 첫 탭(자동재생 정책) 이후 true
let loadedItemId = null;    // 현재 플레이어에 로드된 큐 항목 id
let loadedKey = null;       // `${항목 id}:${영상 id}` — 막힌 영상이 다른 버전으로 바뀌면 달라진다
let playedKey = null;       // 실제로 PLAYING 까지 간 loadedKey (가짜 ENDED 무시용)
let retryOnVisible = false; // 백그라운드 탭에서 난 오류는 건너뛰지 않고 돌아왔을 때 다시 시도
let state = { nowPlaying: null, queue: [] };
let ws = null;

const $ = (id) => document.getElementById(id);

// ---- 호스트 토큰: URL → localStorage (북마크로 다시 열어도 동작) ----
const HOST_KEY = 'karaoke.hostToken';
let hostToken = new URLSearchParams(location.search).get('host');
try {
  if (hostToken) localStorage.setItem(HOST_KEY, hostToken);
  else hostToken = localStorage.getItem(HOST_KEY);
} catch (e) { /* 저장소 사용 불가 */ }
if (location.search) history.replaceState(null, '', '/player');
if (!hostToken) $('nohost').classList.remove('hidden');

// ---- WebSocket ----
function connect() {
  ws = new WebSocket(`ws://${location.host}/ws?host=${encodeURIComponent(hostToken || '')}`);
  ws.onopen = () => $('conn').classList.remove('off');
  ws.onclose = () => {
    $('conn').classList.add('off');
    setTimeout(connect, 1000);
  };
  ws.onmessage = (e) => {
    const msg = JSON.parse(e.data);
    if (msg.type === 'state') {
      state = msg;
      render();
      renderPanel();
      renderJoin();
      sync();
    } else if (msg.type === 'command' && playerReady) {
      runCommand(msg);
    }
  };
}

function send(obj) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
}

function runCommand(cmd) {
  switch (cmd.action) {
    case 'pause': player.pauseVideo(); break;
    case 'play': player.playVideo(); break;
    case 'seekBy': player.seekTo(Math.max(0, player.getCurrentTime() + cmd.seconds), true); break;
    case 'seekTo': player.seekTo(Math.max(0, cmd.seconds), true); break;
  }
  setTimeout(reportProgress, 300);
}

// ---- 진행 상황 보고 (동승자 페이지 진행 바) ----
function reportProgress() {
  if (!playerReady || loadedItemId === null) return;
  const s = player.getPlayerState();
  send({
    type: 'progress',
    itemId: loadedItemId,
    position: player.getCurrentTime() || 0,
    duration: player.getDuration() || 0,
    playing: s === YT.PlayerState.PLAYING,
  });
}
setInterval(reportProgress, 5000);

// ---- YouTube IFrame Player ----
function onYouTubeIframeAPIReady() {
  player = new YT.Player('player', {
    width: '100%',
    height: '100%',
    playerVars: { playsinline: 1, rel: 0, origin: location.origin },
    events: {
      onReady: () => { playerReady = true; sync(); },
      onStateChange: (e) => {
        if (e.data === YT.PlayerState.PLAYING) playedKey = loadedKey;
        if (e.data === YT.PlayerState.ENDED && loadedItemId !== null) {
          // 로드 중에 튀는 ENDED 로 곡이 바로 넘어가지 않도록, 실제 재생된 곡만 종료 처리
          if (playedKey === loadedKey) send({ type: 'ended', itemId: loadedItemId });
          else notice('재생이 시작되지 않았어요. 화면을 탭하거나 스킵하세요');
        } else {
          reportProgress();
        }
      },
      onError: (e) => {
        const np = state.nowPlaying;
        console.warn('player error', e.data, loadedItemId);
        if (loadedItemId === null) return;
        if (document.hidden) {
          // 크롬은 백그라운드 탭 재생을 막을 수 있다 → 영상 문제로 보지 않고 돌아오면 재시도
          retryOnVisible = true;
          return;
        }
        // 100: 없음/비공개, 101·150: 퍼가기 불가, 2·5·153 등 기타 → 다음 곡으로
        notice(`재생할 수 없어요 (오류 ${e.data})${np ? ' · ' + label(np) : ''} → 다른 버전을 찾는 중`);
        send({ type: 'error', itemId: loadedItemId, code: e.data });
      },
    },
  });
}

// 서버 상태의 nowPlaying과 플레이어를 맞춘다.
function sync() {
  if (!playerReady || !started) return;
  const np = state.nowPlaying;
  if (!np) {
    if (loadedItemId !== null) {
      player.stopVideo();
      loadedItemId = null;
      loadedKey = null;
    }
    return;
  }
  const key = `${np.id}:${np.videoId}`;
  if (key !== loadedKey) {
    // 같은 예약인데 영상만 바뀜 = 막힌 영상을 다른 버전으로 대체
    if (np.id === loadedItemId && np.replacedFrom) {
      notice(`${np.replacedFrom} 버전이 막혀 있어 ${np.brand} ${np.variant || '기본'} 버전으로 재생해요`);
    }
    loadedItemId = np.id;
    loadedKey = key;
    player.loadVideoById(np.videoId);
  }
}

// ---- 화면 ----
const label = (item) => `${item.title} - ${item.artist}`;

function render() {
  const np = state.nowPlaying;
  $('idle').classList.toggle('hidden', !!np);
  $('now').classList.toggle('hidden', !np);
  $('now').textContent = np ? `♪ ${label(np)} · ${np.nickname}` : '';

  const ol = $('queue');
  ol.replaceChildren(...state.queue.slice(0, 5).map((item) => {
    const li = document.createElement('li');
    li.textContent = `${label(item)} · ${item.nickname}`;
    return li;
  }));
}

$('start').addEventListener('click', () => {
  started = true;
  $('start').classList.add('hidden');
  sync();
});

$('skip').addEventListener('click', () => send({ type: 'skip' }));

document.addEventListener('visibilitychange', () => {
  if (document.hidden || !retryOnVisible || !playerReady) return;
  retryOnVisible = false;
  const np = state.nowPlaying;
  if (np && np.id === loadedItemId) player.loadVideoById(np.videoId);
});

let noticeTimer = null;
function notice(msg) {
  $('notice').textContent = msg;
  $('notice').classList.remove('hidden');
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => $('notice').classList.add('hidden'), 6000);
}

connect();

// ---- 호스트 패널: 검색·예약, 예약 목록 관리, 재생 제어 (호스트 토큰으로 요청) ----
const fmt = (sec) => {
  sec = Math.max(0, Math.floor(sec || 0));
  return `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`;
};
const versionLabel = (s) =>
  `${s.brand}${s.karaokeNo ? ' ' + s.karaokeNo : ''} · ${s.variant || '기본 반주'} · ${fmt(s.durationSec)}`;

function el(tag, props = {}, ...children) {
  const node = Object.assign(document.createElement(tag), props);
  node.append(...children.filter((c) => c != null));
  return node;
}

async function hostApi(method, path, body) {
  const headers = { 'X-Host': hostToken || '' };
  if (body) headers['Content-Type'] = 'application/json';
  const res = await fetch(path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw Object.assign(new Error(`HTTP ${res.status}`), { status: res.status, text });
  }
  return res.status === 204 ? null : res.json();
}

function hostError(e) {
  if (e.status === 401 || e.status === 403) notice('호스트 인증이 없어요. 호스트 앱에서 플레이어를 다시 열어 주세요');
  else if (e.status === 409) notice('재생 오류가 났던 곡이라 예약할 수 없어요');
  else notice('요청에 실패했어요');
}

let panelOpen = false;
function setPanel(open) {
  panelOpen = open;
  $('panel').classList.toggle('hidden', !open);
  $('upnext').classList.toggle('hidden', open);
  if (open) {
    renderPanel();
    setTimeout(() => $('pq').focus(), 50);
  }
}
$('openPanel').addEventListener('click', () => setPanel(true));
$('closePanel').addEventListener('click', () => setPanel(false));

// 재생 제어
$('panel').querySelector('.panel-ctrl').addEventListener('click', async (ev) => {
  const btn = ev.target.closest('button');
  if (!btn) return;
  let act = btn.dataset.act;
  if (btn.id === 'pPlay' && playerReady) {
    act = player.getPlayerState() === YT.PlayerState.PLAYING ? 'pause' : 'play';
  }
  const qs = btn.dataset.sec ? `?seconds=${btn.dataset.sec}` : '';
  try { await hostApi('POST', `/api/player/${act}${qs}`); } catch (e) { hostError(e); }
  setTimeout(renderPanelCtrl, 400);
});

function renderPanelCtrl() {
  const playing = playerReady && player.getPlayerState && player.getPlayerState() === YT.PlayerState.PLAYING;
  $('pPlay').textContent = playing ? '⏸' : '▶';
}

// 검색
let pSeq = 0;
let pField = 'all'; // all | title | artist
$('pField').addEventListener('click', (ev) => {
  const btn = ev.target.closest('button');
  if (!btn) return;
  pField = btn.dataset.field;
  for (const b of $('pField').children) b.classList.toggle('on', b === btn);
  panelSearch();
});
let pTimer = null;
$('pq').addEventListener('input', () => { clearTimeout(pTimer); pTimer = setTimeout(panelSearch, 300); });
$('pq').addEventListener('keydown', (ev) => {
  if (ev.key === 'Enter') { clearTimeout(pTimer); panelSearch(); $('pq').blur(); }
});

async function panelSearch() {
  const q = $('pq').value.trim();
  const seq = ++pSeq;
  if (!q) { $('pResults').replaceChildren(); return; }
  try {
    const groups = await hostApi('GET', `/api/search?q=${encodeURIComponent(q)}&field=${pField}`);
    if (seq !== pSeq) return;
    if (!groups.length) {
      $('pResults').replaceChildren(el('li', { className: 'muted', textContent: '검색 결과가 없어요' }));
      return;
    }
    $('pResults').replaceChildren(...groups.map((g) => {
      const def = g.versions[0];
      const versions = el('div', { className: 'versions', hidden: true },
        ...g.versions.map((v) => el('button', { textContent: `＋ ${versionLabel(v)}`, onclick: () => reserve(v) })));
      return el('li', {},
        el('div', { className: 'row' },
          el('button', { className: 'main', onclick: () => reserve(def) },
            el('div', { className: 't', textContent: `${g.title} - ${g.artist}` }),
            el('div', { className: 's', textContent: versionLabel(def) })),
          g.versions.length > 1
            ? el('button', { className: 'small', textContent: `버전 ${g.versions.length}`, onclick: () => { versions.hidden = !versions.hidden; } })
            : null),
        g.versions.length > 1 ? versions : null);
    }));
  } catch (e) {
    hostError(e);
  }
}

async function reserve(song) {
  try {
    await hostApi('POST', '/api/queue', { videoId: song.videoId });
    notice(`예약: ${song.title}`);
  } catch (e) {
    hostError(e);
  }
}

// 예약 목록 관리 (호스트: 모든 곡 순서 변경·삭제)
function renderPanel() {
  if (!panelOpen) return;
  renderPanelCtrl();
  const items = state.queue.map((item, i) => el('li', {},
    el('div', { className: 'row' },
      el('div', { className: 'main' },
        el('div', { className: 't', textContent: `${i + 1}. ${label(item)}` }),
        el('div', { className: 's', textContent: `${item.nickname} · ${item.brand} ${item.variant || '기본'} · ${fmt(item.durationSec)}` })),
      el('button', { className: 'small', textContent: '▲', onclick: () => move(item, -1) }),
      el('button', { className: 'small', textContent: '▼', onclick: () => move(item, 1) }),
      el('button', { className: 'small', textContent: '✕', onclick: () => removeItem(item) }))));
  $('pQueue').replaceChildren(...(items.length ? items : [el('li', { className: 'muted', textContent: '대기 중인 곡이 없어요' })]));
}

async function move(item, delta) {
  try { await hostApi('POST', `/api/queue/${item.id}/move?delta=${delta}`); } catch (e) { hostError(e); }
}

async function removeItem(item) {
  try { await hostApi('DELETE', `/api/queue/${item.id}`); } catch (e) { hostError(e); }
}


// ---- 동승자 예약 페이지 QR (현재 핫스팟 IP 기준, 주기적으로 갱신) ----
const QR_KEY = 'karaoke.showQr';
let showQr = true;
try { showQr = localStorage.getItem(QR_KEY) !== '0'; } catch (e) { /* 기본값 */ }
let joinInfo = null;
let joinError = '';

async function refreshJoin() {
  if (!hostToken) {
    joinError = '호스트 인증이 없어 QR을 만들 수 없어요 (호스트 앱에서 플레이어를 열어 주세요)';
    renderJoin();
    return;
  }
  try {
    joinInfo = await hostApi('GET', '/api/join-info');
    joinError = joinInfo.guestUrl ? '' : '핫스팟(또는 와이파이)을 켜면 예약 QR이 나타나요';
    const t = Date.now(); // IP 가 바뀌면 QR 도 바뀌므로 캐시 방지
    const q = `host=${encodeURIComponent(hostToken)}&t=${t}`;
    if (joinInfo.guestUrl) $('qrGuest').src = `/qr/guest.svg?${q}`;
  } catch (e) {
    joinInfo = null;
    joinError = `QR 정보를 불러오지 못했어요 (${e.status ? 'HTTP ' + e.status : e.message})`;
  }
  renderJoin();
}

$('qrGuest').addEventListener('error', () => {
  joinError = 'QR 이미지를 불러오지 못했어요';
  renderJoin();
});
$('qrGuest').addEventListener('load', () => {
  if (joinError === 'QR 이미지를 불러오지 못했어요') { joinError = ''; renderJoin(); }
});

function renderJoin() {
  const ok = !joinError && joinInfo && joinInfo.guestUrl;
  $('qrBox').hidden = !ok;
  $('qrMsg').hidden = !!ok;
  $('qrMsg').textContent = joinError;
  // QR 이 안 나오는 이유도 보여주도록, 숨기기를 누른 경우에만 감춘다
  $('join').classList.toggle('hidden', !showQr);
  $('join').classList.toggle('big', !state.nowPlaying); // 대기 중엔 크게
  $('toggleQr').textContent = showQr ? 'QR 숨기기' : 'QR 보이기';
}

$('toggleQr').addEventListener('click', () => {
  showQr = !showQr;
  try { localStorage.setItem(QR_KEY, showQr ? '1' : '0'); } catch (e) { /* 저장 불가 */ }
  renderJoin();
});

refreshJoin();
setInterval(refreshJoin, 30000);
