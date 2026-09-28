// 플레이어 페이지: 서버 큐(WebSocket)를 받아 YouTube IFrame Player로 재생한다.
// 반드시 호스트 폰의 크롬에서 열어야 프리미엄(광고 없음)이 적용된다.

let player = null;
let playerReady = false;
let started = false;        // 첫 탭(자동재생 정책) 이후 true
let loadedItemId = null;    // 현재 플레이어에 로드된 큐 항목 id
let state = { nowPlaying: null, queue: [] };
let ws = null;

const $ = (id) => document.getElementById(id);

// ---- WebSocket ----
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
      render();
      sync();
    } else if (msg.type === 'command' && playerReady) {
      if (msg.action === 'pause') player.pauseVideo();
      if (msg.action === 'play') player.playVideo();
    }
  };
}

function send(obj) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
}

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
  $('now').textContent = np ? `♪ ${label(np)}` : '';

  const ol = $('queue');
  ol.replaceChildren(...state.queue.slice(0, 5).map((item) => {
    const li = document.createElement('li');
    li.textContent = label(item);
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
