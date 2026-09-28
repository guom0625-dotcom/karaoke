// 동승자 페이지: 닉네임 → 곡 검색(곡 단위) → 예약, 예약 현황·진행 바 실시간 확인,
// 본인 예약 취소, 본인 곡이 재생 중이면 일시정지·빨리감기·스킵.
//
// 접속 주소: /guest?room=<방 토큰>  (QR). 방 토큰·세션은 localStorage 에 저장.
// ?profile=<이름> 을 붙이면 저장소를 분리해 한 브라우저에서 여러 동승자를 흉내 낼 수 있다 (테스트용).

const $ = (id) => document.getElementById(id);
const params = new URLSearchParams(location.search);
const profile = params.get('profile') || 'default';
const STORE_KEY = `karaoke.guest.${profile}`;

let store = loadStore();
let me = null;                 // { publicId, nickname }
let state = { nowPlaying: null, queue: [] };
let progress = null;           // { itemId, position, duration, playing } + receivedAt
let ws = null;

// ---- 저장소 ----
function loadStore() {
  try { return JSON.parse(localStorage.getItem(STORE_KEY)) || {}; } catch (e) { return {}; }
}
function saveStore() {
  try { localStorage.setItem(STORE_KEY, JSON.stringify(store)); } catch (e) { /* 저장 불가 */ }
}
if (params.get('room')) {
  if (store.room !== params.get('room')) {
    // 새 방: 이전 세션은 서버에서 이미 사라졌다 (닉네임만 기억)
    store = { room: params.get('room'), lastNickname: store.nickname || store.lastNickname };
  }
  saveStore();
  history.replaceState(null, '', profile === 'default' ? '/guest' : `/guest?profile=${encodeURIComponent(profile)}`);
}

// ---- API ----
async function api(method, path, body) {
  const headers = { 'X-Room': store.room || '' };
  if (store.secret) headers['X-Session'] = store.secret;
  if (body) headers['Content-Type'] = 'application/json';
  const res = await fetch(path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    const err = new Error(`HTTP ${res.status}`);
    err.status = res.status;
    err.text = text;
    throw err;
  }
  return res.status === 204 ? null : res.json();
}

function handleError(e, forbiddenMsg) {
  if (e.status === 401) { me = null; askNickname(); return; }
  if (e.status === 403 && e.text === 'room') { showBlocked(); return; }
  if (e.status === 403) { toast(forbiddenMsg || '권한이 없어요'); return; }
  if (e.status === 404) { toast('이미 끝났거나 없는 곡이에요'); return; }
  toast('연결에 문제가 있어요. 잠시 후 다시 시도해 주세요');
}

// ---- 시작 ----
async function init() {
  if (!store.room) return showBlocked();
  if (store.secret) {
    try { me = await api('GET', '/api/me'); } catch (e) { me = null; }
  }
  if (me) start(); else askNickname();
}

function start() {
  $('app').hidden = false;
  $('me').hidden = false;
  $('me').textContent = `${me.nickname} ✎`;
  if (!ws) connect();
  render();
}

function showBlocked() {
  $('app').hidden = true;
  $('nickDialog').hidden = true;
  $('blocked').hidden = false;
}

// ---- 닉네임 ----
function askNickname() {
  $('nickInput').value = (me && me.nickname) || store.nickname || store.lastNickname || '';
  $('nickDialog').hidden = false;
  setTimeout(() => $('nickInput').focus(), 50);
}

$('me').addEventListener('click', askNickname);

$('nickForm').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const nickname = $('nickInput').value.trim();
  if (!nickname) return;
  try {
    if (me) {
      me = await api('POST', '/api/me', { nickname });
    } else {
      const s = await api('POST', '/api/session', { nickname });
      store.secret = s.secret;
      me = { publicId: s.publicId, nickname: s.nickname };
    }
    store.nickname = me.nickname;
    saveStore();
    $('nickDialog').hidden = true;
    start();
  } catch (e) {
    handleError(e);
  }
});

// ---- WebSocket (예약 현황·진행 상황) ----
function connect() {
  ws = new WebSocket(`ws://${location.host}/ws`);
  ws.onopen = () => $('conn').classList.remove('off');
  ws.onclose = () => {
    $('conn').classList.add('off');
    setTimeout(connect, 1000);
  };
  ws.onmessage = (e) => {
    const msg = JSON.parse(e.data);
    if (msg.type === 'state') {
      state = msg;
      if (progress && (!state.nowPlaying || progress.itemId !== state.nowPlaying.id)) progress = null;
      render();
    } else if (msg.type === 'progress') {
      progress = msg.progress ? { ...msg.progress, receivedAt: performance.now() } : null;
      renderNow();
    }
  };
}

// ---- 시간 계산 ----
const fmt = (sec) => {
  sec = Math.max(0, Math.floor(sec || 0));
  return `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`;
};

/** 보고받은 위치 + 경과 시간으로 현재 위치를 추정 (보고는 몇 초 간격) */
function currentPosition() {
  const np = state.nowPlaying;
  if (!np || !progress || progress.itemId !== np.id) return { position: 0, duration: np ? np.durationSec : 0, playing: false };
  const elapsed = progress.playing ? (performance.now() - progress.receivedAt) / 1000 : 0;
  const duration = progress.duration || np.durationSec;
  return { position: Math.min(duration, progress.position + elapsed), duration, playing: progress.playing };
}

/** 대기 순서 i 번째 곡이 시작할 때까지 남은 시간(초) */
function waitSeconds(i) {
  const np = state.nowPlaying;
  let sec = 0;
  if (np) {
    const p = currentPosition();
    sec += Math.max(0, p.duration - p.position);
  }
  for (let k = 0; k < i; k++) sec += state.queue[k].durationSec;
  return sec;
}

const isMine = (item) => me && item.ownerId === me.publicId;
const songLabel = (s) => `${s.title} - ${s.artist}`;
const versionLabel = (s) =>
  `${s.brand}${s.karaokeNo ? ' ' + s.karaokeNo : ''} · ${s.variant || '기본 반주'} · ${fmt(s.durationSec)}`;

// ---- 화면 ----
function el(tag, props = {}, ...children) {
  const node = Object.assign(document.createElement(tag), props);
  node.append(...children.filter((c) => c != null));
  return node;
}

function render() {
  renderNow();
  renderQueue();
}

function renderNow() {
  const np = state.nowPlaying;
  $('npTitle').textContent = np ? songLabel(np) : '예약된 곡이 없어요';
  $('npSub').textContent = np ? `${np.nickname}${np.variant ? ' · ' + np.variant : ''} · ${np.brand}` : '';
  const mine = !!(np && isMine(np));
  $('controls').hidden = !mine;
  $('bar').classList.toggle('seekable', mine);
  const p = currentPosition();
  $('btnPlay').dataset.act = p.playing ? 'pause' : 'play';
  $('btnPlay').textContent = p.playing ? '⏸ 일시정지' : '▶ 재생';
  updateBar();
}

function updateBar() {
  const p = currentPosition();
  $('barFill').style.width = p.duration > 0 ? `${(p.position / p.duration) * 100}%` : '0';
  $('tNow').textContent = fmt(p.position);
  $('tTotal').textContent = fmt(p.duration);
}

function renderQueue() {
  const items = state.queue.map((item, i) => {
    const mine = isMine(item);
    const wait = Math.round(waitSeconds(i) / 60);
    return el('li', { className: mine ? 'mine' : '' },
      el('div', { className: 'row' },
        el('span', { className: 'idx', textContent: `${i + 1}` }),
        el('div', { className: 'row-main' },
          el('div', { className: 'row-title', textContent: songLabel(item) + (item.variant ? ` [${item.variant}]` : '') }),
          el('div', { className: 'row-sub', textContent: `${item.nickname} · ${wait < 1 ? '곧' : `약 ${wait}분 후`}` })),
        mine ? el('button', { className: 'x', textContent: '취소', onclick: () => cancel(item) }) : null));
  });
  $('queue').replaceChildren(...(items.length ? items : [el('li', { className: 'muted', textContent: '대기 중인 곡이 없어요' })]));

  const np = state.nowPlaying;
  const myIdx = state.queue.findIndex(isMine);
  const myCount = state.queue.filter(isMine).length;
  let summary = '';
  if (np && isMine(np)) summary = '· 지금 내 차례!';
  else if (myIdx >= 0) summary = `· 내 예약 ${myCount}곡, 다음 차례 약 ${Math.max(1, Math.round(waitSeconds(myIdx) / 60))}분 후`;
  $('mySummary').textContent = summary;
}

// 진행 바는 로컬에서 부드럽게 움직이고, 대기 시간은 가끔 갱신
setInterval(updateBar, 250);
setInterval(renderQueue, 15000);

// ---- 재생 제어 (본인 곡일 때만 표시) ----
$('controls').addEventListener('click', async (ev) => {
  const btn = ev.target.closest('button');
  if (!btn) return;
  const act = btn.dataset.act;
  const qs = btn.dataset.sec ? `?seconds=${btn.dataset.sec}` : '';
  try {
    await api('POST', `/api/player/${act}${qs}`);
  } catch (e) {
    handleError(e, '지금 부르는 사람만 조작할 수 있어요');
  }
});

$('bar').addEventListener('click', async (ev) => {
  const np = state.nowPlaying;
  if (!np || !isMine(np)) return;
  const p = currentPosition();
  if (!(p.duration > 0)) return;
  const rect = $('bar').getBoundingClientRect();
  const to = ((ev.clientX - rect.left) / rect.width) * p.duration;
  try {
    await api('POST', `/api/player/seekTo?seconds=${to.toFixed(1)}`);
  } catch (e) {
    handleError(e, '지금 부르는 사람만 조작할 수 있어요');
  }
});

// ---- 예약·취소 ----
async function reserve(song) {
  try {
    await api('POST', '/api/queue', { videoId: song.videoId });
    toast(`예약했어요: ${song.title}`);
  } catch (e) {
    handleError(e);
  }
}

async function cancel(item) {
  if (!confirm(`'${item.title}' 예약을 취소할까요?`)) return;
  try {
    await api('DELETE', `/api/queue/${item.id}`);
  } catch (e) {
    handleError(e, '본인 예약만 취소할 수 있어요');
  }
}

// ---- 검색 ----
let searchSeq = 0;
let searchTimer = null;

$('q').addEventListener('input', () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(search, 300);
});
$('q').addEventListener('keydown', (ev) => {
  if (ev.key === 'Enter') { clearTimeout(searchTimer); search(); $('q').blur(); }
});

async function search() {
  const q = $('q').value.trim();
  const seq = ++searchSeq;
  if (!q) { $('results').replaceChildren(); return; }
  try {
    const groups = await api('GET', `/api/search?q=${encodeURIComponent(q)}`);
    if (seq !== searchSeq) return; // 더 최근 검색이 있음
    renderResults(groups);
  } catch (e) {
    handleError(e);
  }
}

function renderResults(groups) {
  if (!groups.length) {
    $('results').replaceChildren(el('li', { className: 'muted', textContent: '검색 결과가 없어요' }));
    return;
  }
  $('results').replaceChildren(...groups.map((g) => {
    const def = g.versions[0];
    const versions = el('ul', { className: 'versions', hidden: true },
      ...g.versions.map((v) => el('li', {},
        el('button', { textContent: `＋ ${versionLabel(v)}`, onclick: () => reserve(v) }))));
    const more = g.versions.length > 1
      ? el('button', {
          className: 'pill',
          textContent: `버전 ${g.versions.length} ▾`,
          onclick: () => { versions.hidden = !versions.hidden; },
        })
      : null;
    return el('li', {},
      el('div', { className: 'row' },
        el('button', { className: 'row-main', onclick: () => reserve(def) },
          el('div', { className: 'row-title', textContent: `${g.title} - ${g.artist}` }),
          el('div', { className: 'row-sub', textContent: versionLabel(def) })),
        more),
      g.versions.length > 1 ? versions : null);
  }));
}

// ---- 토스트 ----
let toastTimer = null;
function toast(msg) {
  $('toast').textContent = msg;
  $('toast').hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { $('toast').hidden = true; }, 2200);
}

init();
