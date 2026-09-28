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
