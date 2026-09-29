#!/usr/bin/env python3
"""
TJ·금영 공식 채널 노래방 영상 목록 수집기 (GitHub Actions 에서 매일 실행).

결과는 GitHub Release `songs-db` 의 자산으로 올린다.
  songs.jsonl.gz   한 줄에 영상 하나 (앱이 받아서 제목을 파싱해 곡 DB 에 합친다)
  songs-meta.json  {"updatedAt": ..., "count": ...}  앱이 새 목록인지 확인하는 용도
  crawl-state.json 다음 실행이 이어서 진행할 상태

수집 순서 (하루 유닛 예산 안에서, 할당량 초과 시 상태 저장 후 종료):
  1. 채널별 최신 업로드(UU…)   : 처음엔 끝까지(약 2만 개 제한), 이후 새 영상만
  2. 인기 영상(UULP…)·채널 재생목록 : 30일마다 다시 훑기
  3. 기존 레코드 재확인        : 유튜브 API 정책(데이터 주기적 갱신) — 매일 일부, 삭제된 영상 제거
  4. 남은 예산으로 기간별 검색   : search.list(order=date, publishedBefore) 로 목록 API 가 못 주는 오래된 영상 채우기

API 키는 앱과 같은 안드로이드 제한 키를 쓴다 (패키지명·서명 SHA-1 헤더를 붙인다).
"""
import gzip
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

API = "https://www.googleapis.com/youtube/v3/"
CHANNELS = {"UCZUhx8ClCv6paFW7qi3qljg": "TJ", "UCDqaUIUSJP5EVMEI178Zfag": "KY"}
MIN_DURATION = 61
REFRESH_DAYS = 30
REFRESH_PER_RUN = 5000  # 기존 레코드 재확인 개수 (50개당 1유닛)
SEARCH_PAGES_PER_WINDOW = 10  # search.list 는 한 검색어에 최대 약 500개

KEY = os.environ["YT_API_KEY"]
HEADERS = {
    "X-Android-Package": os.environ.get("YT_ANDROID_PACKAGE", "com.guom.karaoke"),
    "X-Android-Cert": os.environ.get("YT_ANDROID_CERT", "7B23E1BE94209D052F519FD875598DDC0310DF23"),
}
BUDGET = int(os.environ.get("UNIT_BUDGET", "7000"))
OUT = os.environ.get("OUT_DIR", "out")
used = 0


class QuotaDone(Exception):
    pass


def call(resource, cost=1, **params):
    """API 호출. 예산을 넘거나 할당량이 끝나면 QuotaDone."""
    global used
    if used + cost > BUDGET:
        raise QuotaDone(f"budget {BUDGET} reached")
    params["key"] = KEY
    url = API + resource + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers=HEADERS)
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                used += cost
                return json.load(r)
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", "replace")
            used += cost
            if e.code == 403 and "quotaExceeded" in body:
                raise QuotaDone("quotaExceeded")
            if e.code == 404:
                return None
            if e.code >= 500 and attempt < 2:
                time.sleep(3)
                continue
            raise RuntimeError(f"{resource} HTTP {e.code}: {body[:300]}")
        except urllib.error.URLError:
            if attempt < 2:
                time.sleep(3)
                continue
            raise


def iso_duration(s):
    import re
    m = re.fullmatch(r"P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?", s or "")
    if not m:
        return 0
    d, h, mi, se = (int(x or 0) for x in m.groups())
    return d * 86400 + h * 3600 + mi * 60 + se


def karaoke_no(description):
    import re
    m = re.search(r"곡번호\.?\s*(\d+)", description or "")
    return m.group(1) if m else None


def now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


# ---------------------------------------------------------------- 저장소
def load(path, default):
    try:
        if path.endswith(".gz"):
            with gzip.open(path, "rt", encoding="utf-8") as f:
                return [json.loads(line) for line in f if line.strip()]
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return default


records = {r["v"]: r for r in load(f"{OUT}/songs.jsonl.gz", [])}
state = load(f"{OUT}/crawl-state.json", {})
state.setdefault("playlists", {})
state.setdefault("search", {})
state.setdefault("refreshCursor", 0)
stats = {"added": 0, "removed": 0, "refreshed": 0}


def fetch_details(ids):
    """영상 상세를 받아 레코드로 저장 (채널 화이트리스트·짧은 영상 제외). 50개당 1유닛."""
    today = now_iso()[:10]
    for i in range(0, len(ids), 50):
        chunk = ids[i:i + 50]
        res = call("videos", part="snippet,contentDetails,status,statistics", id=",".join(chunk), maxResults=50)
        for v in (res or {}).get("items", []):
            sn = v.get("snippet", {})
            ch = sn.get("channelId")
            dur = iso_duration(v.get("contentDetails", {}).get("duration"))
            if ch not in CHANNELS or dur < MIN_DURATION:
                continue
            is_new = v["id"] not in records
            records[v["id"]] = {
                "v": v["id"],
                "c": ch,
                "t": sn.get("title", ""),
                "n": karaoke_no(sn.get("description")),
                "d": dur,
                "e": 1 if v.get("status", {}).get("embeddable") else 0,
                "p": sn.get("publishedAt"),
                "views": int(v.get("statistics", {}).get("viewCount", 0) or 0),
                "r": today,
            }
            if is_new:
                stats["added"] += 1


# ---------------------------------------------------------------- 1·2. 재생목록
def sync_playlist(pid, refresh_days):
    """refresh_days=None: 최신 업로드(증분). 숫자: 끝까지 훑고 그 기간 뒤 다시."""
    st = state["playlists"].setdefault(pid, {"fullDone": False, "pageToken": None, "completedAt": 0})
    if refresh_days is not None and st["fullDone"] and time.time() - st["completedAt"] < refresh_days * 86400:
        return
    incremental = refresh_days is None and st["fullDone"]
    token = None if st["fullDone"] else st["pageToken"]
    while True:
        params = {"part": "contentDetails", "playlistId": pid, "maxResults": 50}
        if token:
            params["pageToken"] = token
        res = call("playlistItems", **params)
        if res is None:  # 없는 재생목록
            st.update(fullDone=True, pageToken=None, completedAt=time.time())
            return
        ids = [it["contentDetails"]["videoId"] for it in res.get("items", [])]
        known = [i for i in ids if i in records]
        fetch_details([i for i in ids if i not in records])
        token = res.get("nextPageToken")
        if not incremental:
            st["pageToken"] = token
            if token is None:
                st.update(fullDone=True, completedAt=time.time())
        if token is None or (incremental and known):
            return


def channel_playlists(channel_id):
    out, token = [], None
    while True:
        params = {"part": "id", "channelId": channel_id, "maxResults": 50}
        if token:
            params["pageToken"] = token
        res = call("playlists", **params)
        out += [p["id"] for p in (res or {}).get("items", [])]
        token = (res or {}).get("nextPageToken")
        if not token:
            return out


# ---------------------------------------------------------------- 3. 재확인
def refresh_existing():
    today = now_iso()[:10]
    ids = sorted(v for v, r in records.items() if r.get("r") != today)  # 오늘 받은 영상은 건너뜀
    if not ids:
        return
    start = state["refreshCursor"] % len(ids)
    batch = (ids[start:] + ids[:start])[:REFRESH_PER_RUN]
    for i in range(0, len(batch), 50):
        chunk = batch[i:i + 50]
        res = call("videos", part="snippet,contentDetails,status,statistics", id=",".join(chunk), maxResults=50)
        alive = {v["id"] for v in (res or {}).get("items", [])}
        for vid in chunk:
            if vid not in alive:  # 삭제·비공개
                records.pop(vid, None)
                stats["removed"] += 1
        for v in (res or {}).get("items", []):
            r = records.get(v["id"])
            if r:
                r["e"] = 1 if v.get("status", {}).get("embeddable") else 0
                r["views"] = int(v.get("statistics", {}).get("viewCount", 0) or 0)
                r["t"] = v.get("snippet", {}).get("title", r["t"])
                r["r"] = now_iso()[:10]
                stats["refreshed"] += 1
        state["refreshCursor"] = (start + i + len(chunk)) % max(1, len(records))


# ---------------------------------------------------------------- 4. 기간별 검색
def search_backfill(channel_id):
    """최신 → 과거로 publishedBefore 를 옮겨 가며 search.list. 페이지당 100유닛."""
    st = state["search"].setdefault(channel_id, {"before": None, "pageToken": None, "pages": 0, "oldest": None, "done": False})
    while not st["done"]:
        params = {"part": "snippet", "channelId": channel_id, "type": "video", "order": "date", "maxResults": 50}
        if st["before"]:
            params["publishedBefore"] = st["before"]
        if st["pageToken"]:
            params["pageToken"] = st["pageToken"]
        res = call("search", cost=100, **params) or {}
        items = res.get("items", [])
        ids = [it["id"]["videoId"] for it in items if it.get("id", {}).get("videoId")]
        fetch_details([i for i in ids if i not in records])
        for it in items:
            p = it.get("snippet", {}).get("publishedAt")
            if p and (st["oldest"] is None or p < st["oldest"]):
                st["oldest"] = p
        st["pages"] += 1
        token = res.get("nextPageToken")
        if token and st["pages"] < SEARCH_PAGES_PER_WINDOW:
            st["pageToken"] = token
            continue
        # 이 기간 창을 다 봤다 → 가장 오래된 영상 이전으로 창을 옮긴다
        if not items or st["oldest"] is None or st["oldest"] == st["before"]:
            st["done"] = True
            break
        oldest = datetime.strptime(st["oldest"], "%Y-%m-%dT%H:%M:%SZ") - timedelta(seconds=1)
        st.update(before=oldest.strftime("%Y-%m-%dT%H:%M:%SZ"), pageToken=None, pages=0)


# ---------------------------------------------------------------- 실행
def main():
    os.makedirs(OUT, exist_ok=True)
    reason = "done"
    try:
        for ch in CHANNELS:
            suffix = ch[2:]
            sync_playlist("UU" + suffix, None)
        for ch in CHANNELS:
            sync_playlist("UULP" + ch[2:], REFRESH_DAYS)
            for pid in channel_playlists(ch):
                sync_playlist(pid, REFRESH_DAYS)
        refresh_existing()
        for ch in CHANNELS:
            search_backfill(ch)
    except QuotaDone as e:
        reason = str(e)
    except Exception as e:  # 예상 못 한 오류여도 여기까지 모은 진행분은 저장한다
        reason = f"error: {e}"

    rows = sorted(records.values(), key=lambda r: r["v"])
    with gzip.open(f"{OUT}/songs.jsonl.gz", "wt", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False, separators=(",", ":")) + "\n")
    with open(f"{OUT}/crawl-state.json", "w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False)
    per = {name: sum(1 for r in rows if r["c"] == ch) for ch, name in CHANNELS.items()}
    with open(f"{OUT}/songs-meta.json", "w", encoding="utf-8") as f:
        json.dump({"updatedAt": now_iso(), "count": len(rows), "perBrand": per}, f)

    backfill = {CHANNELS[c]: ("완료" if s.get("done") else f"{s.get('before') or '최신'} 이전까지 진행")
                for c, s in state["search"].items()}
    print(f"units used: {used}/{BUDGET} ({reason})")
    print(f"records: {len(rows)} {per} · added {stats['added']} · removed {stats['removed']} · refreshed {stats['refreshed']}")
    print(f"backfill: {backfill}")
    return 1 if reason.startswith("error") and not rows else 0


if __name__ == "__main__":
    sys.exit(main())
