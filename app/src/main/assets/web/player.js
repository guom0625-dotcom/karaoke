// 플레이어 페이지: 서버 큐(WebSocket)를 받아 YouTube IFrame Player로 재생한다.
// 반드시 호스트 폰의 크롬에서 열어야 프리미엄(광고 없음)이 적용된다.
// 호스트 앱이 /player?host=<토큰> 으로 연다. 토큰이 있어야 재생 종료·진행 상황을 서버에 알릴 수 있다.

let player = null;
let playerReady = false;
let started = false;        // 첫 탭(자동재생 정책) 이후 true
let loadedItemId = null;    // 현재 플레이어에 로드된 큐 항목 id
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
        if (e.data === YT.PlayerState.ENDED && loadedItemId !== null) {
          send({ type: 'ended', itemId: loadedItemId });
        } else {
          reportProgress();
        }
      },
      onError: (e) => {
        // 100: 없음/비공개, 101·150: 임베드 불가, 2·5·153 등 → 자동 스킵
        console.warn('player error', e.data, loadedItemId);
        if (loadedItemId !== null) send({ type: 'error', itemId: loadedItemId, code: e.data });
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
    }
    return;
  }
  if (np.id !== loadedItemId) {
    loadedItemId = np.id;
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

connect();
