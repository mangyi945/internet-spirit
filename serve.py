import http.server
import socketserver
import threading
import webbrowser
import os
import sys
import socket
import json
import re
import time
import random
import hashlib
import hmac
import asyncio
import urllib.request
import concurrent.futures
import base64
import subprocess

# 可选：老式论坛 / 资源库 / 联机社团（bbs.py）。删掉这个文件也照样能跑。
try:
    import bbs as _bbs
except Exception:
    _bbs = None

# 可选：听书用的语音合成（edge-tts）。没装就退化成浏览器自带语音。
try:
    import edge_tts as _edge
except Exception:
    _edge = None

try:
    sys.stdout.reconfigure(line_buffering=True)
except Exception:
    pass

PORT = 8642
for _a in sys.argv[1:]:
    if _a.isdigit():
        PORT = int(_a)
        break
if getattr(sys, 'frozen', False):
    ROOT = sys._MEIPASS
else:
    ROOT = os.path.dirname(os.path.abspath(__file__))
HUBS_FILE = os.path.join(ROOT, 'hubs.json')
os.chdir(ROOT)

# ---------------- 附近的站点（局域网扫描，访客先逛目录） ----------------
SITES = {'at': 0.0, 'list': []}
SITES_LOCK = threading.Lock()
SITE_TTL = 60


def ensure_local_tts():
    """配置了本地语音但 9880 没起来时，启动时顺手拉起来（后台，不占窗口）。"""
    cfg = tts_local_cfg()
    if not cfg:
        return
    try:
        with socket.create_connection(('127.0.0.1', 9880), timeout=0.4):
            return
    except Exception:
        pass
    exe = os.path.join(ROOT, 'tts-local', 'venv', 'Scripts', 'python.exe')
    cwd = os.path.join(ROOT, 'tts-local', 'src', 'GPT-SoVITS-main')
    conf = os.path.join(cwd, 'GPT_SoVITS', 'configs', 'tts_infer.yaml')
    if not (os.path.isfile(exe) and os.path.isfile(conf)):
        return
    log = os.path.join(ROOT, 'tts-local', 'logs', 'api-auto.log')
    try:
        os.makedirs(os.path.dirname(log), exist_ok=True)
        f = open(log, 'ab')
        flags = 0x00000008 | 0x00000200  # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
        env = dict(os.environ)
        # 关键：不加这个，日文里的「・」会让服务端 print 抛 GBK 编码错误 → 400 → 变成别的声线
        env['PYTHONIOENCODING'] = 'utf-8'
        env['PYTHONUTF8'] = '1'
        subprocess.Popen([exe, 'api_v2.py', '-a', '127.0.0.1', '-p', '9880',
                          '-c', 'GPT_SoVITS/configs/tts_infer.yaml'],
                         cwd=cwd, stdout=f, stderr=f, stdin=subprocess.DEVNULL,
                         env=env, creationflags=flags)
        global TTS_WARM_UNTIL
        TTS_WARM_UNTIL = time.time() + 180
        print('  [语音] 本地语音服务没在跑，已在后台拉起（约 30 秒就绪）')
    except Exception as e:
        print('  [语音] 后台启动失败：%s' % e)


def _lan_ips():
    ips = set()
    try:
        sk = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sk.connect(('8.8.8.8', 80))
        ips.add(sk.getsockname()[0])
        sk.close()
    except Exception:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip and not ip.startswith('127.') and not ip.startswith('169.254'):
                ips.add(ip)
    except Exception:
        pass
    return ips


def _probe_site(ip, port):
    try:
        with socket.create_connection((ip, port), timeout=0.25):
            pass
    except Exception:
        return None
    base = 'http://%s:%d' % (ip, port)
    try:
        with urllib.request.urlopen(base + '/__info', timeout=0.7) as r:
            info = json.loads(r.read().decode('utf-8'))
    except Exception:
        return None
    if not info.get('forum'):
        return None
    name, tag = '', ''
    try:
        with urllib.request.urlopen(base + '/bbs/api/site', timeout=0.9) as r:
            site = (json.loads(r.read().decode('utf-8')) or {}).get('site') or {}
        name = site.get('name') or ''
        tag = site.get('tagline') or ''
    except Exception:
        pass
    return {'url': base, 'host': ip, 'name': name or ip, 'tagline': tag}


def scan_sites():
    now = time.time()
    with SITES_LOCK:
        if now - SITES['at'] < SITE_TTL and SITES['list']:
            return SITES['list']
    port = PORT
    targets = []
    for ip in _lan_ips():
        targets.append(ip)
        prefix = ip.rsplit('.', 1)[0]
        for i in range(1, 255):
            t = prefix + '.' + str(i)
            if t != ip:
                targets.append(t)
    found = []
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=64) as ex:
            for res in ex.map(lambda t: _probe_site(t, port), targets):
                if res:
                    found.append(res)
    except Exception:
        pass
    seen, uniq = set(), []
    mine = _lan_ips()
    for st in found:
        if st['host'] in seen:
            continue
        seen.add(st['host'])
        st['self'] = st['host'] in mine
        uniq.append(st)
    with SITES_LOCK:
        SITES['at'] = time.time()
        SITES['list'] = uniq
    return uniq


# ---------------- 听书：语音合成（和「文乃终端」同一条声线） ----------------
TTS_DIR = os.path.join(ROOT, 'tts-cache')
TTS_VOICE = 'zh-CN-XiaoyiNeural'
TTS_RATE = '-4%'
TTS_PITCH = '+4Hz'
TTS_VOLUME = '-8%'
AUDIO_EXT = {'.mp3': 'audio/mpeg', '.wav': 'audio/wav', '.ogg': 'audio/ogg',
             '.flac': 'audio/flac', '.m4a': 'audio/mp4'}


def sniff_audio(raw):
    """看头几个字节判断音频格式，返回 (扩展名, Content-Type)。"""
    if raw[:4] == b'RIFF' and raw[8:12] == b'WAVE':
        return '.wav', 'audio/wav'
    if raw[:4] == b'fLaC':
        return '.flac', 'audio/flac'
    if raw[:4] == b'OggS':
        return '.ogg', 'audio/ogg'
    if raw[:3] == b'ID3' or raw[:2] in (b'\xff\xfb', b'\xff\xf3', b'\xff\xf2'):
        return '.mp3', 'audio/mpeg'
    if raw[4:8] == b'ftyp':
        return '.m4a', 'audio/mp4'
    return '.mp3', 'audio/mpeg'


async def _tts_save(text, voice, rate, pitch, path):
    comm = _edge.Communicate(text, voice, rate=rate, pitch=pitch, volume=TTS_VOLUME)
    tmp = path + '.part'
    await comm.save(tmp)
    os.replace(tmp, path)


def tts_make(text, voice, rate, pitch):
    """返回 (hash, 是否命中缓存)。"""
    key = hashlib.md5(('%s|%s|%s|%s' % (voice, rate, pitch, text)).encode('utf-8')).hexdigest()
    fp = os.path.join(TTS_DIR, key + '.mp3')
    if os.path.isfile(fp) and os.path.getsize(fp) > 0:
        return key, True
    os.makedirs(TTS_DIR, exist_ok=True)
    asyncio.run(_tts_save(text, voice, rate, pitch, fp))
    return key, False


# ---- 本地离线 TTS（可选）：tts-local.json 指向本地合成服务 ----
TTS_LOCAL_CFG = os.path.join(ROOT, 'tts-local.json')
PACK_DIR = os.path.join(ROOT, 'tts-local', 'packs')
PACK_JSON = os.path.join(ROOT, 'tts-local', 'packs.json')


def _post_process_wav(raw, lowpass=True):
    """本地合成回来的 WAV：轻度低通压一压高频毛刺，再做峰值归一。纯标准库实现。"""
    try:
        if len(raw) < 48 or raw[:4] != b'RIFF' or raw[8:12] != b'WAVE':
            return raw
        import struct
        # 找 fmt / data
        pos = 12
        fmt = None
        data_off = data_len = None
        while pos + 8 <= len(raw):
            cid = raw[pos:pos + 4]
            clen = struct.unpack('<I', raw[pos + 4:pos + 8])[0]
            body = pos + 8
            if cid == b'fmt ':
                fmt = struct.unpack('<HHIIHH', raw[body:body + 16])
            elif cid == b'data':
                data_off, data_len = body, min(clen, len(raw) - body)
                break
            pos = body + clen + (clen & 1)
        if not fmt or data_off is None or fmt[0] != 1 or fmt[5] != 16:
            return raw
        ch, rate = fmt[1], fmt[2]
        if ch < 1 or ch > 2 or rate < 8000:
            return raw
        n = data_len // 2
        vals = list(struct.unpack('<%dh' % n, raw[data_off:data_off + n * 2]))

        if lowpass:
            # 二阶低通（RBJ）
            f0 = min(11500.0, rate * 0.36)
            w0 = 2.0 * 3.141592653589793 * f0 / rate
            cw, sw = __import__('math').cos(w0), __import__('math').sin(w0)
            alpha = sw / (2 * 0.707)
            b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = b0
            a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
            b0 /= a0; b1 /= a0; b2 /= a0; a1 /= a0; a2 /= a0
            x1 = x2 = y1 = y2 = 0.0
            peak = 1
            for i in range(n):
                x = vals[i] / 32768.0
                y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2, x1 = x1, x; y2, y1 = y1, y
                v = int(max(-32768, min(32767, y * 32767)))
                vals[i] = v
                if v > peak: peak = v
                elif -v > peak: peak = -v
        else:
            # 语音包：声音偏闷，跳过低通，加一点高频架子（+4dB @ 3.5kHz）再归一
            f0 = 3500.0
            A = 10 ** (4.0 / 40.0)
            w0 = 2.0 * 3.141592653589793 * f0 / rate
            cw, sw = __import__('math').cos(w0), __import__('math').sin(w0)
            alpha = sw / 2 * ((A + 1.0 / A) * (1.0 / 1.0 - 1.0) + 2) ** 0.5
            sa = A ** 0.5
            b0 = A * ((A + 1) + (A - 1) * cw + 2 * sa * alpha)
            b1 = -2 * A * ((A - 1) + (A + 1) * cw)
            b2 = A * ((A + 1) + (A - 1) * cw - 2 * sa * alpha)
            a0 = (A + 1) - (A - 1) * cw + 2 * sa * alpha
            a1 = 2 * ((A - 1) - (A + 1) * cw)
            a2 = (A + 1) - (A - 1) * cw - 2 * sa * alpha
            b0 /= a0; b1 /= a0; b2 /= a0; a1 /= a0; a2 /= a0
            x1 = x2 = y1 = y2 = 0.0
            peak = 1
            for i in range(n):
                x = vals[i] / 32768.0
                y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2, x1 = x1, x; y2, y1 = y1, y
                v = int(max(-32768, min(32767, y * 32767)))
                vals[i] = v
                if v > peak: peak = v
                elif -v > peak: peak = -v
        # 峰值归一到 -1 dBFS
        if peak > 0:
            g = (32767 * 0.89) / peak
            if g < 1.5:
                vals = [int(max(-32768, min(32767, v * g))) for v in vals]
        out = bytearray(raw)
        out[data_off:data_off + n * 2] = struct.pack('<%dh' % n, *vals)
        return bytes(out)
    except Exception:
        return raw


def packs_load():
    try:
        with open(PACK_JSON, 'r', encoding='utf-8-sig') as f:
            data = json.load(f)
        return data if isinstance(data, list) else []
    except Exception:
        return []


def packs_save(items):
    try:
        os.makedirs(os.path.dirname(PACK_JSON), exist_ok=True)
        tmp = PACK_JSON + '.tmp'
        with open(tmp, 'w', encoding='utf-8') as f:
            json.dump(items, f, ensure_ascii=False, indent=1)
        os.replace(tmp, PACK_JSON)
    except Exception:
        pass


def packs_get(pid):
    for it in packs_load():
        if it.get('id') == pid:
            return it
    return None
TTS_WARM_UNTIL = 0.0
TTS_LOCAL_LOCK = threading.Lock()


def tts_log(msg):
    """听书回落/失败留痕，方便排查「声音突然变了」。"""
    try:
        path = os.path.join(ROOT, 'logs', 'tts.log')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'a', encoding='utf-8') as f:
            f.write('%s  %s\n' % (time.strftime('%m-%d %H:%M:%S'), msg))
    except Exception:
        pass


def _relay_log(msg):
    try:
        path = os.path.join(ROOT, 'logs', 'relay.log')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'a', encoding='utf-8') as f:
            f.write('%s  %s\n' % (time.strftime('%m-%d %H:%M:%S'), msg))
    except Exception:
        pass


def tts_local_cfg():
    try:
        # 记事本存出来的 utf-8 会带 BOM，这里用 utf-8-sig 兼容
        with open(TTS_LOCAL_CFG, 'r', encoding='utf-8-sig') as f:
            cfg = json.load(f)
        return cfg if cfg.get('url') else None
    except Exception:
        return None


def tts_local(text, lang='', rate='', pack=None):
    """调本地合成服务：返回 mp3 字节，失败返回 None。

    约定：POST JSON（默认 {"text": "..."}，可用 body 追加 reference 等字段），
    返回 audio/* 原始字节，或 JSON {"audio": "<base64>"} / {"url": "..."}。
    """
    cfg = tts_local_cfg()
    if not cfg:
        return None
    body = dict(cfg.get('body') or {})
    if pack:
        if pack.get('file'):
            body['ref_audio_path'] = pack['file']
        if pack.get('text'):
            body['prompt_text'] = pack['text']
        if pack.get('lang'):
            body['prompt_lang'] = pack['lang']
    body['text'] = text
    if lang:
        body['text_lang'] = lang
    if rate:
        try:
            n = int(str(rate).strip().rstrip('%'))
            body['speed_factor'] = max(0.5, min(2.0, 1 + n / 100.0))
        except Exception:
            pass
    req = urllib.request.Request(
        cfg['url'],
        data=json.dumps(body).encode('utf-8'),
        headers={'Content-Type': 'application/json'},
    )
    with urllib.request.urlopen(req, timeout=float(cfg.get('timeout') or 60)) as r:
        ctype = (r.headers.get('Content-Type') or '').lower()
        raw = r.read()
    if 'json' in ctype:
        j = json.loads(raw.decode('utf-8') or '{}')
        if j.get('audio'):
            import base64 as _b64
            raw = _b64.b64decode(j['audio'])
        elif j.get('url'):
            with urllib.request.urlopen(j['url'], timeout=60) as r2:
                raw = r2.read()
        else:
            return None
    return raw if raw else None


# ---------------- 会合点（只换地址，不传数据） ----------------
SIGNAL = {}
SIGNAL_LOCK = threading.Lock()
SIGNAL_TTL = 600

# ==================== 盲会合（Blind Rendezvous，2026-11-04 PoC） ====================
# 独立命名空间，与 /__signal 不共享记录。中枢只存密文：看不到 secret，也看不到 SDP 明文。
# 协议 v1：
#   room    = SHA-256(secret) 的 hex（secret 是 128 位一次性随机值，hex 32 字符）
#   payload = AES-256-GCM(由 secret 经 HKDF 派生的 key, SDP JSON)，IV 12 字节随行
#   PUT /__blind/put  {room, role: offer|answer, payload, nonce}
#   GET /__blind/get?room=..&role=..&wait=..  → 该房间该角色最近若干条密文（可选长轮询）
BLIND = {}
BLIND_LOCK = threading.Lock()

# 限流（每 IP 滑动窗口；公网部署前的轻量防刷）
BLIND_RATE = {}
BLIND_RATE_LOCK = threading.Lock()
BLIND_PUT_PER_MIN = 30
BLIND_GET_PER_MIN = 300

def _blind_allow(ip, limit, now):
    with BLIND_RATE_LOCK:
        hits = [t for t in BLIND_RATE.get(ip, []) if now - t < 60]
        if len(hits) >= limit:
            BLIND_RATE[ip] = hits
            return False
        hits.append(now)
        BLIND_RATE[ip] = hits
        if len(BLIND_RATE) > 10000:   # 表本身也限大小
            for k in [k for k, v in BLIND_RATE.items() if not v]:
                BLIND_RATE.pop(k, None)
    return True
BLIND_TTL = 600            # 秒：服务端过期（记录创建/更新时间超过即清）
BLIND_MAX_ROOMS = 1000     # 房间总量上限，防当网盘
BLIND_MAX_PER = 8          # 每房间每角色最多存几条（重试会追加，读方取最新能解开的）
BLIND_MAX_PAYLOAD = 65536  # 单条密文上限

# 通用写操作限流（公网部署）：只对资源重的 POST 端点，回环/本机反代不受限
POST_RATE = {}
POST_RATE_LOCK = threading.Lock()
POST_LIMITS = {
    '/__tts': 30,          # 语音合成吃显卡/CPU
    '/__room/new': 20,     # 建房
    '/__voicepacks': 20,   # 上传语音包
    '/__signal/new': 60,   # 建会合码
    '/__signal/put': 120,  # 会合写入
    '/__board/post': 20,   # 发公告（含附件）
    '/__friend/req': 30,   # 加好友请求
}


def _real_ip(self):
    try:
        ip = str(self.client_address[0] or '')
        # 只有直连方是本机（Caddy/nginx/cloudflared 反代）时才信这些头，防伪造
        if ip in ('127.0.0.1', '::1', '::ffff:127.0.0.1'):
            xff = (self.headers.get('X-Forwarded-For') or '').split(',')[0].strip()
            if xff:
                return xff
            cf = (self.headers.get('Cf-Connecting-IP') or '').strip()
            if cf:
                return cf
            xr = (self.headers.get('X-Real-IP') or '').strip()
            if xr:
                return xr
    except Exception:
        pass
    return str(self.client_address[0] or '') if self.client_address else ''


# ---- 入侵哨兵：公网来源连续失败就自动关公网（只断公网，本机/局域网不受影响）----
GUARD_LOCK = threading.Lock()
GUARD_BAD = {}
GUARD_TRIPPED_AT = 0.0
GUARD_WINDOW = 60
GUARD_LIMIT = 30

def _guard_is_public(ip):
    ip = str(ip or "").strip()
    if not ip:
        return False
    if ip in ("127.0.0.1", "::1", "::ffff:127.0.0.1"):
        return False
    priv = ("10.", "192.168.", "172.16.", "172.17.", "172.18.", "172.19.",
            "172.20.", "172.21.", "172.22.", "172.23.", "172.24.", "172.25.",
            "172.26.", "172.27.", "172.28.", "172.29.", "172.30.", "172.31.",
            "fe80:", "::ffff:10.", "::ffff:192.168.")
    return not ip.startswith(priv)

def _guard_note(ip, path, code):
    global GUARD_TRIPPED_AT
    if code not in (400, 401, 403, 404):
        return
    if not _guard_is_public(ip):
        return
    now = time.time()
    trip = False
    with GUARD_LOCK:
        arr = GUARD_BAD.get(ip)
        if arr is None:
            arr = GUARD_BAD[ip] = []
        arr.append(now)
        arr[:] = [t for t in arr if now - t < GUARD_WINDOW]
        n = len(arr)
        if n >= GUARD_LIMIT:
            GUARD_BAD.pop(ip, None)
            if now - GUARD_TRIPPED_AT > 300:
                GUARD_TRIPPED_AT = now
                trip = True
    if trip:
        _guard_trip(ip, n)

def _guard_trip(ip, n):
    try:
        HUB_STATE["guard_trip"] = time.time()
    except Exception:
        pass
    try:
        os.makedirs(os.path.join(ROOT, "logs"), exist_ok=True)
        with open(os.path.join(ROOT, "logs", "guard.log"), "a", encoding="utf-8") as f:
            f.write("%s  触发防护 ip=%s 失败=%d 动作=关闭公网\n" % (time.strftime("%m-%d %H:%M:%S"), ip, n))
    except Exception:
        pass
    try:
        req = urllib.request.Request("http://127.0.0.1:8666/public/stop", method="POST")
        urllib.request.urlopen(req, timeout=5)
    except Exception:
        pass


def _post_rate_ok(self, path, now):
    with HUB_LOCK:
        if not HUB_CFG.get('rate_limit', True):
            return True
    limit = POST_LIMITS.get(path)
    if not limit:
        return True
    if TUNNEL_MODE and path in ('/__tts', '/__mp3', '/__mp3concat'):
        limit = min(limit, 4)
    ip = _real_ip(self)
    if not ip or ip in ('127.0.0.1', '::1', '::ffff:127.0.0.1'):
        return True
    key = ip + '|' + path
    with POST_RATE_LOCK:
        hits = [t for t in POST_RATE.get(key, []) if now - t < 60]
        if len(hits) >= limit:
            POST_RATE[key] = hits
            return False
        hits.append(now)
        POST_RATE[key] = hits
        if len(POST_RATE) > 20000:
            for k in [k for k, v in POST_RATE.items() if not v]:
                POST_RATE.pop(k, None)
    return True

def _blind_sweep(now):
    dead = [k for k, r in BLIND.items() if now - r['at'] > BLIND_TTL]
    for k in dead:
        BLIND.pop(k, None)
    if len(BLIND) > BLIND_MAX_ROOMS:
        for k in sorted(BLIND, key=lambda x: BLIND[x]['at'])[:len(BLIND) - BLIND_MAX_ROOMS]:
            BLIND.pop(k, None)
USAGE_LOCK = threading.Lock()
USAGE = {'in': 0, 'out': 0, 'req': 0, 'since': time.time()}
USAGE_WIN = []   # [(ts, inBytes, outBytes)] 最近 10 秒，用来算实时速率

def _usage_add(kind, n):
    if n <= 0:
        return
    try:
        with USAGE_LOCK:
            USAGE[kind] += n
    except Exception:
        pass

def _usage_snapshot():
    now = time.time()
    with USAGE_LOCK:
        u = dict(USAGE)
        USAGE_WIN.append((now, u["in"], u["out"]))
        while USAGE_WIN and now - USAGE_WIN[0][0] > 10:
            USAGE_WIN.pop(0)
        win = list(USAGE_WIN)
    dt = max(0.001, now - win[0][0]) if len(win) > 1 else 1.0
    d_in = (win[-1][1] - win[0][1]) if len(win) > 1 else 0
    d_out = (win[-1][2] - win[0][2]) if len(win) > 1 else 0
    return {
        'ok': True, 'in': u['in'], 'out': u['out'], 'req': u['req'],
        'since': u['since'], 'seconds': int(now - u['since']),
        'inRate': int(d_in / dt), 'outRate': int(d_out / dt),
    }

LAN_PEERS = {}
LAN_HI = {}
LAN_SIG = {}
LAN_TTL = 45

PROTO = "http"


def global_ipv6():
    """本机用于 IPv6 出口的地址（没有就返回 None）。"""
    s = socket.socket(socket.AF_INET6, socket.SOCK_DGRAM)
    try:
        s.connect(("2001:4860:4860::8888", 80))
        a = s.getsockname()[0]
        if a.startswith("fe80") or a == "::1":
            return None
        return a
    except Exception:
        return None
    finally:
        try:
            s.close()
        except Exception:
            pass


LAN_BEACON_PORT = 38421
LAN_BEACON_NAME = ''

def lan_beacon_loop():
    """持续往局域网广播自己的地址，手机 App 收到后自动把这里设为服务节点。"""
    try:
        import socket as _sock
    except Exception:
        return
    _ip = lan_ip()
    if _ip.startswith('127.'):
        time.sleep(3)
        return
    msg_base = 'HLJJS1|http://%s:%d|' % (_ip, PORT)
    name = LAN_BEACON_NAME or os.environ.get('COMPUTERNAME') or 'PC'
    msg = (msg_base + name).encode('utf-8')
    so = _sock.socket(_sock.AF_INET, _sock.SOCK_DGRAM)
    try:
        so.setsockopt(_sock.SOL_SOCKET, _sock.SO_BROADCAST, 1)
    except Exception:
        pass
    while True:
        try:
            so.sendto(msg, ('255.255.255.255', LAN_BEACON_PORT))
        except Exception:
            pass
        try:
            ip = lan_ip()
            if ip and ip.count('.') == 3 and not ip.startswith('127.'):
                so.sendto(msg, (ip.rsplit('.', 1)[0] + '.255', LAN_BEACON_PORT))
        except Exception:
            pass
        time.sleep(3)

def lan_ip():
    ip = ""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
    except Exception:
        ip = ""
    finally:
        try:
            s.close()
        except Exception:
            pass
    if ip and not ip.startswith("127."):
        return ip
    # 连不上外网时，从本机网卡里挑一个局域网地址
    try:
        for item in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            cand = item[4][0]
            if not cand or cand.startswith("127."):
                continue
            if cand.startswith(("192.168.", "10.", "172.16.", "172.17.", "172.18.", "172.19.",
                                "172.2", "172.30.", "172.31.")):
                return cand
        for item in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            cand = item[4][0]
            if cand and not cand.startswith("127."):
                return cand
    except Exception:
        pass
    return ip or "127.0.0.1"


def _gc():
    now = time.time()
    for k in list(SIGNAL):
        if now - SIGNAL[k]["at"] > SIGNAL_TTL:
            SIGNAL.pop(k, None)


def _new_code():
    with SIGNAL_LOCK:
        _gc()
        for _ in range(200):
            c = "%04d" % random.randint(0, 9999)
            if c not in SIGNAL:
                SIGNAL[c] = {"offer": None, "answer": None, "at": time.time()}
                return c
    raise RuntimeError("no free code")


try:
    import ssl
    import certifi as _certifi
    _SSL_CTX = ssl.create_default_context(cafile=_certifi.where())
except Exception:
    try:
        import ssl as _ssl2
        _SSL_CTX = _ssl2._create_unverified_context()
    except Exception:
        _SSL_CTX = None


UA_DESKTOP = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
              '(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0')
UA_MOBILE = ('Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 '
             '(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36')


def _pick_ua(handler):
    """按发起请求的客户端选 UA：电脑给桌面 UA，手机给移动 UA。"""
    try:
        ua = (handler.headers.get('User-Agent') or '')
    except Exception:
        ua = ''
    if any(k in ua for k in ('Mobile', 'Android', 'iPhone', 'iPad')):
        return UA_MOBILE
    return UA_DESKTOP


def _proxy_inject(html, base):
    """给代理页面注入 <base> 和链接拦截脚本"""
    inject = ('<base href="' + base + '">' +
              '<script>(function(){' +
              # target=_blank 一律改成当前标签内打开；window.open 交给父页面开新标签
              'try{document.querySelectorAll("a[target=_blank]").forEach(function(a){a.target="_self";});}catch(e){}' +
              'try{window.open=function(u){try{if(u)parent.postMessage({type:"hljjs-proxy",url:new URL(u,document.baseURI).href},"*");}catch(e){}return null;};}catch(e){}' +
              'document.addEventListener("DOMContentLoaded",function(){try{document.querySelectorAll("a[target=_blank]").forEach(function(a){a.target="_self";});}catch(e){}});'
              'document.addEventListener("click",function(e){'
              'var a=e.target.closest&&e.target.closest("a");if(!a)return;'
              'var h=a.getAttribute("href");if(!h||h.charAt(0)==="#"||h.indexOf("javascript:")===0)return;'
              'try{var abs=new URL(h,document.baseURI).href;'
              'if(!/^https?:/.test(abs))return;e.preventDefault();'
              'parent.postMessage({type:"hljjs-proxy",url:abs},"*");}catch(err){}},true);'
              'var __m={};function __r(u,k){try{if(!u||__m[u])return;if(!/^https?:/i.test(u))return;__m[u]=1;'
              'parent.postMessage({type:"hljjs-media",url:u,kind:k||""},"*");}catch(e){}}'
              'function __is(u){return /\.(mp4|m4v|webm|mkv|mov|mp3|m4a|aac|ogg|wav|flac|m3u8|mpd|ts)(\?|$)/i.test(u||"");}'
              'try{var __f=window.fetch;if(__f){window.fetch=function(){try{var a=arguments[0];var u=typeof a==="string"?a:(a&&a.url);if(__is(u))__r(u,"fetch");}catch(e){}return __f.apply(this,arguments);};}}catch(e){}'
              'try{var __o=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){try{if(__is(u))__r(u,"xhr");}catch(e){}return __o.apply(this,arguments);};}catch(e){}'
              'function __scan(){try{document.querySelectorAll("video,audio,source").forEach(function(el){var u=el.currentSrc||el.src||el.getAttribute("src");if(__is(u))__r(u,el.tagName.toLowerCase());});'
              'document.querySelectorAll("a[href]").forEach(function(a){if(__is(a.href))__r(a.href,"link");});}catch(e){}}'
              'document.addEventListener("DOMContentLoaded",__scan);setTimeout(__scan,1200);setTimeout(__scan,3500);'
              'try{new MutationObserver(__scan).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}'
              '})();</script>')
    m = re.search(r'<head[^>]*>', html, re.I)
    if m:
        return html[:m.end()] + inject + html[m.end():]
    m2 = re.search(r'<html[^>]*>', html, re.I)
    if m2:
        return html[:m2.end()] + '<head>' + inject + '</head>' + html[m2.end():]
    return inject + html


# ==================== 社团中枢（WebSocket 中转） ====================
# 和 P2P 社团并存：P2P 社团的"中枢"是团长设备；这里的"中枢"是这台电脑。
# 成员用 WebSocket 连进来，服务器把一条消息/一块文件原样转给同社团的其他人。
# 协议和 P2P 完全一致（文本=JSON 控制消息，二进制=文件块），所以前端只是换了一条"假 DataChannel"。
ROOM_LOCK = threading.Lock()
ROOMS = {}
OPEN_ROOMS = {}
RELAY = {}                       # pair -> {id: RoomConn}（一比一兜底中继）
RELAY_LOCK = threading.Lock()


def relay_other(pair, mid):
    with RELAY_LOCK:
        slot = RELAY.get(pair) or {}
        for k, v in slot.items():
            if k != mid:
                return v
    return None
FRIENDS = {}
FRIEND_REQ = {}
FRIEND_LOCK = threading.Lock()
OPEN_LOCK = threading.Lock()
OPEN_TTL = 30 * 60                      # code -> {code,name,cap,token,at,members:{id:RoomConn}}
ROOM_MAX_IDLE = 30 * 60         # 空社团 30 分钟回收
ROOM_MAX_LIFE = 12 * 3600
ROOM_MAX_FRAME = 4 * 1024 * 1024

HUB_CFG_FILE = os.environ.get('HLJJS_HUB_CFG') or os.path.join(ROOT, 'hub-config.json')
HUB_DATA_DIR = os.environ.get('HLJJS_HUB_DATA') or os.path.join(os.path.expanduser('~'), '.hljjs-hub')

# ---------- 中枢身份（P-256，纯 Python，无第三方依赖） ----------
try:
    import hubid as _hubid
    _HUBKEY = _hubid.HubKey.load_or_create(os.path.join(HUB_DATA_DIR, 'hubkey.json'))
except Exception as _e:
    _hubid = None
    _HUBKEY = None
    try:
        print('hub identity unavailable:', _e)
    except Exception:
        pass


def hub_identity():
    """对外公布的中枢身份：hubPub(JWK) / hubFp / keyReady"""
    if not _HUBKEY:
        return {'keyReady': False, 'hubPub': None, 'hubFp': ''}
    return {'keyReady': True, 'hubPub': _HUBKEY.pub_jwk(), 'hubFp': _HUBKEY.fp()}


def hub_sign(msg):
    try:
        return _HUBKEY.sign_b64(msg) if _HUBKEY else ''
    except Exception:
        return ''


def hub_verify(pub, msg, sig):
    try:
        return bool(_hubid and _hubid.verify(pub, msg, sig))
    except Exception:
        return False


# ---------- 串联签名：link-req / link-ok ----------
_LINK_NONCE = {}


def _pub_fp(pub):
    try:
        canon = json.dumps({'kty': 'EC', 'crv': 'P-256', 'x': pub.get('x'), 'y': pub.get('y')},
                           separators=(',', ':'), sort_keys=False)
        return hashlib.sha256(canon.encode()).hexdigest()[:12]
    except Exception:
        return ''


def hub_link_req_canon(rid, from_id, from_url, ts, nonce):
    return 'link-req|%s|%s|%s|%d|%s' % (rid, from_id, from_url, int(ts), nonce)


def hub_link_ok_canon(rid, peer_id, peer_url, key, ts, nonce):
    return 'link-ok|%s|%s|%s|%s|%d|%s' % (rid, peer_id, peer_url, key, int(ts), nonce)


def hub_link_signed_request(rid, from_id, from_url, from_name):
    ts = int(time.time() * 1000)
    nonce = os.urandom(8).hex()
    payload = {'reqId': rid, 'fromId': from_id, 'fromUrl': from_url, 'fromName': from_name,
               'fromPubUrl': hub_extra_public_url(),
               'ts': ts, 'nonce': nonce,
               'fromPub': (_HUBKEY.pub_jwk() if _HUBKEY else None),
               'fromFp': (_HUBKEY.fp() if _HUBKEY else '')}
    payload['sig'] = hub_sign(hub_link_req_canon(rid, from_id, from_url, ts, nonce))
    return payload


def hub_link_verify_request(body):
    """返回 (ok, err, extra)。没有签名=旧中枢：允许，但 verified=False。"""
    pub = body.get('fromPub') or None
    sig = str(body.get('sig') or '')
    if not pub or not sig:
        return True, '', {'verified': False}
    try:
        ts = int(body.get('ts') or 0)
        nonce = str(body.get('nonce') or '')[:32]
        fp = str(body.get('fromFp') or '')
        if not ts or not nonce or abs(time.time() * 1000 - ts) > 300000:
            return False, '申请签名信息不完整或已过期', {}
        canon = hub_link_req_canon(body.get('reqId'), body.get('fromId'), body.get('fromUrl'), ts, nonce)
        if not hub_verify(pub, canon, sig):
            return False, '对方中枢签名验证失败', {}
        if _pub_fp(pub) != fp:
            return False, '指纹和公钥对不上', {}
        if _LINK_NONCE.get(nonce):
            return False, '重复的申请', {}
        _LINK_NONCE[nonce] = time.time()
        if len(_LINK_NONCE) > 500:
            for k in list(_LINK_NONCE.keys())[:200]:
                _LINK_NONCE.pop(k, None)
        return True, '', {'verified': True, 'pub': pub, 'fp': fp}
    except Exception as e:
        return False, '验签出错：' + str(e)[:60], {}


def hub_link_signed_peer(rid, key):
    own = hub_link_info()
    ts = int(time.time() * 1000)
    nonce = os.urandom(8).hex()
    peer = {'id': own['id'], 'url': own['url'], 'name': own['name'],
            'pubUrl': own.get('pubUrl') or '',
            'pub': own.get('hubPub'), 'fp': own.get('hubFp') or '',
            'ts': ts, 'nonce': nonce}
    peer['sig'] = hub_sign(hub_link_ok_canon(rid, own['id'], own['url'], key, ts, nonce))
    return peer


def hub_link_verify_peer(rid, key, peer):
    pub = (peer or {}).get('pub') or None
    sig = str((peer or {}).get('sig') or '')
    if not pub or not sig:
        return {'verified': False}
    try:
        ts = int(peer.get('ts') or 0)
        nonce = str(peer.get('nonce') or '')[:32]
        if not ts or not nonce or abs(time.time() * 1000 - ts) > 300000:
            return {'verified': False, 'error': '回执签名过期'}
        canon = hub_link_ok_canon(rid, peer.get('id'), peer.get('url'), key, ts, nonce)
        if not hub_verify(pub, canon, sig):
            return {'verified': False, 'error': '对方回执签名验证失败'}
        fp = _pub_fp(pub)
        if peer.get('fp') and peer.get('fp') != fp:
            return {'verified': False, 'error': '回执指纹和公钥对不上'}
        return {'verified': True, 'fp': fp, 'pub': pub}
    except Exception as e:
        return {'verified': False, 'error': '回执验签出错' + str(e)[:40]}

HUB_CONNS_FILE = os.path.join(HUB_DATA_DIR, 'conns.json')
HUB_BANS_FILE = os.path.join(HUB_DATA_DIR, 'bans.json')
HUB_LOCK = threading.Lock()
HUB_CONN_LOCK = threading.Lock()
HUB_STATE = {'open': True, 'door': True}
HUB_CFG = {'id': '', 'name': '', 'maxRooms': 0, 'maxMembers': 0, 'maxPerRoom': 30, 'rate_limit': True}
HUB_PUBLIC_URL = os.environ.get('HLJJS_PUBLIC_URL', '').strip().rstrip('/')
HUB_ADMIN_TOKEN = os.environ.get('HLJJS_ADMIN_TOKEN', '').strip()
TUNNEL_MODE = (os.environ.get('HLJJS_TUNNEL') or '').strip().lower() in ('1', 'true', 'yes', 'on')
def _load_admin_token():
    tok = HUB_ADMIN_TOKEN
    if tok:
        return tok
    try:
        p = os.path.join(HUB_DATA_DIR, 'admin-token.txt')
        if os.path.isfile(p):
            v = (open(p, encoding='utf-8').read() or '').strip()
            if v:
                return v
        v = hashlib.sha1(os.urandom(32)).hexdigest()[:32]
        os.makedirs(HUB_DATA_DIR, exist_ok=True)
        with open(p, 'w', encoding='utf-8') as f:
            f.write(v + '\n')
        return v
    except Exception:
        return ''
HUB_ADMIN_TOKEN = _load_admin_token()
HUB_CONNS = {}
HUB_PRESENCE = {}
HUB_PRESENCE_LOCK = threading.Lock()
HUB_PRESENCE_TTL = 75
HUB_BANS = set()
BOARD_DIR = os.path.join(HUB_DATA_DIR, 'board')
BOARD_LOCK = threading.Lock()
BOARD_MAX_POSTS = 200
BOARD_MAX_FILE = 12 * 1024 * 1024
BOARD_MAX_TEXT = 4000
MSG_FILE = os.path.join(BOARD_DIR, 'msgs.json')
MSG_LOCK = threading.Lock()
MSG_MAX = 300
MSG_MAX_LEN = 500
MSG_RATE = {}
HUB_PEER_FILE = os.path.join(HUB_DATA_DIR, 'links.json')

# ---------- 离线暂存（只存端到端加密信封，中枢无法解密） ----------
HUB_OFFLINE_FILE = os.path.join(HUB_DATA_DIR, 'offline.json')
HUB_OFFLINE = {}
HUB_OFFLINE_LOCK = threading.Lock()
HUB_OFFLINE_STATE = {'loaded': False}
HUB_OFFLINE_TTL = 3 * 24 * 3600
HUB_OFFLINE_MAX_PER_TO = 200
HUB_OFFLINE_MAX_LEN = 128 * 1024


def hub_offline_load():
    if HUB_OFFLINE_STATE.get('loaded'):
        return
    HUB_OFFLINE_STATE['loaded'] = True
    rows = _hub_read_json(HUB_OFFLINE_FILE, [])
    if not isinstance(rows, list):
        rows = []
    now = time.time()
    with HUB_OFFLINE_LOCK:
        HUB_OFFLINE.clear()
        for r in rows:
            try:
                if now - float(r.get('at') or 0) <= float(r.get('ttl') or HUB_OFFLINE_TTL):
                    HUB_OFFLINE.setdefault(str(r.get('to') or '')[:64], []).append(r)
            except Exception:
                pass


def hub_offline_save():
    try:
        with HUB_OFFLINE_LOCK:
            rows = [r for v in HUB_OFFLINE.values() for r in v][:4000]
        _hub_write_json(HUB_OFFLINE_FILE, rows)
    except Exception:
        pass


def hub_offline_post(to, frm, env, ttl=None):
    hub_offline_load()
    to = str(to or '')[:64]
    frm = str(frm or '')[:64]
    env = str(env or '')
    if not to or not frm or not env or len(env) > HUB_OFFLINE_MAX_LEN:
        return 0
    now = time.time()
    try:
        ttl = int(ttl or HUB_OFFLINE_TTL)
    except Exception:
        ttl = HUB_OFFLINE_TTL
    ttl = max(60, min(7 * 24 * 3600, ttl))
    row = {'to': to, 'from': frm, 'env': env, 'at': now, 'ttl': ttl}
    with HUB_OFFLINE_LOCK:
        lst = HUB_OFFLINE.setdefault(to, [])
        lst.append(row)
        if len(lst) > HUB_OFFLINE_MAX_PER_TO:
            del lst[:len(lst) - HUB_OFFLINE_MAX_PER_TO]
        for k in list(HUB_OFFLINE.keys()):
            HUB_OFFLINE[k] = [x for x in HUB_OFFLINE[k]
                              if now - float(x.get('at') or 0) <= float(x.get('ttl') or HUB_OFFLINE_TTL)]
            if not HUB_OFFLINE[k]:
                HUB_OFFLINE.pop(k, None)
    hub_offline_save()
    return 1


def hub_offline_poll(to):
    hub_offline_load()
    to = str(to or '')[:64]
    now = time.time()
    with HUB_OFFLINE_LOCK:
        lst = HUB_OFFLINE.pop(to, []) or []
        out = [x for x in lst if now - float(x.get('at') or 0) <= float(x.get('ttl') or HUB_OFFLINE_TTL)]
    if out:
        hub_offline_save()
    return out

HUB_PEER_LOCK = threading.Lock()
HUB_PEERS = {}
HUB_LINK_REQS = {}
HUB_LINK_OUT = {}


def _hub_data_ensure():
    try:
        os.makedirs(HUB_DATA_DIR, exist_ok=True)
    except Exception:
        pass


def _board_dir():
    try:
        os.makedirs(BOARD_DIR, exist_ok=True)
    except Exception:
        pass
    return BOARD_DIR


def board_index():
    try:
        _board_dir()
        j = _hub_read_json(os.path.join(BOARD_DIR, 'index.json'), [])
        return j if isinstance(j, list) else []
    except Exception:
        return []


def board_save_index(rows):
    try:
        _hub_data_ensure()
        _hub_write_json(os.path.join(BOARD_DIR, 'index.json'), rows[:BOARD_MAX_POSTS])
    except Exception:
        pass


def msg_list():
    try:
        _board_dir()
        j = _hub_read_json(MSG_FILE, [])
        return j if isinstance(j, list) else []
    except Exception:
        return []


def msg_add(m):
    with MSG_LOCK:
        rows = msg_list()
        rows.insert(0, m)
        rows = rows[:MSG_MAX]
        _hub_data_ensure()
        _hub_write_json(MSG_FILE, rows)
    return m


def board_add(post):
    with BOARD_LOCK:
        rows = board_index()
        rows.insert(0, post)
        dropped = rows[BOARD_MAX_POSTS:]
        rows = rows[:BOARD_MAX_POSTS]
        board_save_index(rows)
        for r in dropped:
            try:
                os.remove(os.path.join(BOARD_DIR, str(r.get('id')) + '.bin'))
            except Exception:
                pass
    return post


def _hub_read_json(path, default):
    try:
        with open(path, 'r', encoding='utf-8') as f:
            return json.load(f)
    except Exception:
        return default


def _hub_write_json(path, obj):
    try:
        _hub_data_ensure()
        tmp = path + '.tmp'
        with open(tmp, 'w', encoding='utf-8') as f:
            json.dump(obj, f, ensure_ascii=False, indent=2)
        os.replace(tmp, path)
        return True
    except Exception:
        return False


def hub_cfg_load():
    j = _hub_read_json(HUB_CFG_FILE, {})
    if not isinstance(j, dict):
        j = {}

    def _int(k, lo, hi, default):
        try:
            v = int(j.get(k, default))
        except Exception:
            v = default
        return max(lo, min(hi, v))

    with HUB_LOCK:
        HUB_CFG['id'] = str(j.get('id') or '').strip()[:32]
        HUB_CFG['name'] = str(j.get('name') or '')[:24]
        HUB_CFG['maxRooms'] = _int('maxRooms', 0, 5000, 0)
        HUB_CFG['maxMembers'] = _int('maxMembers', 0, 100000, 0)
        HUB_CFG['maxPerRoom'] = _int('maxPerRoom', 2, 30, 30)
        _rl = os.environ.get('HLJJS_RATE_LIMIT', '').strip()
        if _rl == '0':
            HUB_CFG['rate_limit'] = False
        elif _rl == '1':
            HUB_CFG['rate_limit'] = True
        else:
            HUB_CFG['rate_limit'] = bool(j.get('rate_limit', True))
        if not HUB_CFG.get('id'):
            HUB_CFG['id'] = hashlib.sha1(os.urandom(16)).hexdigest()[:12]
            _hub_write_json(HUB_CFG_FILE, dict(HUB_CFG))
    rows = _hub_read_json(HUB_CONNS_FILE, [])
    if isinstance(rows, dict):
        rows = list(rows.values())
    with HUB_CONN_LOCK:
        HUB_CONNS.clear()
        for r in (rows if isinstance(rows, list) else [])[:500]:
            if not isinstance(r, dict) or not r.get('id'):
                continue
            HUB_CONNS[str(r['id'])[:64]] = {
                'id': str(r['id'])[:64],
                'name': str(r.get('name') or '')[:32],
                'first': float(r.get('first') or 0),
                'last': float(r.get('last') or 0),
                'online': False,
                'banned': bool(r.get('banned')),
            }
    bans = _hub_read_json(HUB_BANS_FILE, [])
    if isinstance(bans, dict):
        bans = list(bans.keys())
    with HUB_CONN_LOCK:
        HUB_BANS.clear()
        for b in (bans if isinstance(bans, list) else [])[:500]:
            if b:
                HUB_BANS.add(str(b)[:64])
    peers = _hub_read_json(HUB_PEER_FILE, [])
    with HUB_PEER_LOCK:
        HUB_PEERS.clear()
        for p in (peers if isinstance(peers, list) else [])[:200]:
            if not isinstance(p, dict) or not p.get('id') or not p.get('url'):
                continue
            pid = str(p['id'])[:64]
            HUB_PEERS[pid] = {
                'id': pid,
                'url': str(p['url']).strip().rstrip('/')[:300],
                'name': str(p.get('name') or '')[:24],
                'key': str(p.get('key') or '')[:32],
                'since': float(p.get('since') or 0),
                'pubUrl': str(p.get('pubUrl') or '')[:300],
            }


def hub_cfg():
    with HUB_LOCK:
        return dict(HUB_CFG)


def hub_cfg_save(cfg):
    with HUB_LOCK:
        HUB_CFG.update(cfg)
        data = dict(HUB_CFG)
    _hub_write_json(HUB_CFG_FILE, data)
    return data


def hub_open():
    with HUB_LOCK:
        return bool(HUB_STATE['open'])


def hub_door_open():
    with HUB_LOCK:
        return bool(HUB_STATE['door'])


def hub_set_open(v):
    with HUB_LOCK:
        HUB_STATE['open'] = bool(v)


def hub_set_door(v):
    with HUB_LOCK:
        HUB_STATE['door'] = bool(v)


def hub_door_error():
    cfg = hub_cfg()
    nm = cfg.get('name') or ''
    return '中枢已关门' + (('（' + nm + '）') if nm else '') + '：团长暂时不接新的直连'


def hub_conns_save():
    with HUB_CONN_LOCK:
        rows = list(HUB_CONNS.values())[:500]
    _hub_write_json(HUB_CONNS_FILE, rows)


def hub_bans_save():
    with HUB_CONN_LOCK:
        rows = sorted(HUB_BANS)[:500]
    _hub_write_json(HUB_BANS_FILE, rows)


def hub_conn_touch(mid, name, online):
    mid = str(mid or '')[:64]
    if not mid:
        return
    with HUB_CONN_LOCK:
        rec = HUB_CONNS.get(mid)
        if not rec:
            if not online:
                return
            rec = {'id': mid, 'name': str(name or '')[:32], 'first': time.time(),
                   'last': time.time(), 'online': False, 'banned': mid in HUB_BANS}
            HUB_CONNS[mid] = rec
        if name:
            rec['name'] = str(name)[:32]
        rec['last'] = time.time()
        if online:
            rec['online'] = True
            rec['banned'] = mid in HUB_BANS
    if not online:
        still = False
        with ROOM_LOCK:
            for r in ROOMS.values():
                if mid in r['members']:
                    still = True
                    break
        with HUB_CONN_LOCK:
            rec = HUB_CONNS.get(mid)
            if rec is not None:
                rec['online'] = bool(still)
    hub_conns_save()


def hub_conns_rows():
    with HUB_CONN_LOCK:
        rows = [dict(v) for v in HUB_CONNS.values()]
    rows.sort(key=lambda r: (0 if r.get('online') else 1, -(r.get('last') or 0)))
    return rows


def hub_presence_touch(mid, name, online=True, ip=''):
    mid = str(mid or '')[:64]
    if not mid:
        return
    now = time.time()
    with HUB_PRESENCE_LOCK:
        for k, v in list(HUB_PRESENCE.items()):
            if now - float(v.get('last') or 0) > HUB_PRESENCE_TTL:
                HUB_PRESENCE.pop(k, None)
        if not online:
            HUB_PRESENCE.pop(mid, None)
            return
        rec = HUB_PRESENCE.get(mid) or {'id': mid, 'first': now}
        rec['name'] = str(name or rec.get('name') or '')[:32]
        rec['last'] = now
        rec['ip'] = str(ip or rec.get('ip') or '')[:64]
        rec['online'] = True
        HUB_PRESENCE[mid] = rec


def hub_presence_rows():
    now = time.time()
    out = []
    with HUB_PRESENCE_LOCK:
        for k, v in list(HUB_PRESENCE.items()):
            if now - float(v.get('last') or 0) > HUB_PRESENCE_TTL:
                HUB_PRESENCE.pop(k, None)
                continue
            out.append({'id': v.get('id') or k, 'name': v.get('name') or '',
                        'first': float(v.get('first') or 0), 'last': float(v.get('last') or 0),
                        'online': True, 'banned': (v.get('id') or k) in HUB_BANS,
                        'client': True, 'ip': v.get('ip') or ''})
    return out


def hub_device_rows(rows=None):
    """把在线客户端按 ip+名字 归并成“设备”，避免一个浏览器多个标签算成好几个人。"""
    rows = rows if rows is not None else hub_conns_merged()
    seen = {}
    for r in rows:
        if not r.get('online'):
            continue
        ip = str(r.get('ip') or '').replace('::ffff:', '')
        name = str(r.get('name') or '')
        # 本机连接如果名字不同，说明是不同来源的页面，按名字区分；其它情况同一个 IP 视作同一台设备
        if ip in ('127.0.0.1', '::1', '') and name:
            key = ip + '|' + name
        else:
            key = ip or name or str(r.get('id') or '')
        seen[key] = seen.get(key, 0) + 1
    return seen


def hub_conns_merged():
    rows = {r.get('id'): dict(r) for r in hub_conns_rows()}
    for p in hub_presence_rows():
        r = rows.get(p.get('id'))
        if r:
            r['online'] = True
            r['client'] = True
            r['last'] = max(float(r.get('last') or 0), float(p.get('last') or 0))
            if p.get('name') and not r.get('name'):
                r['name'] = p['name']
        else:
            rows[p['id']] = p
    out = list(rows.values())
    out.sort(key=lambda r: (0 if r.get('online') else 1, -(float(r.get('last') or 0))))
    return out


def hub_drop_id(mid, reason):
    mid = str(mid or '')
    targets = []
    with ROOM_LOCK:
        for r in ROOMS.values():
            c = r['members'].get(mid)
            if c is not None:
                targets.append(c)
    for c in targets:
        try:
            c.close_socket(reason)
        except Exception:
            pass
    return len(targets)


def hub_public_url():
    if HUB_PUBLIC_URL:
        return HUB_PUBLIC_URL
    return 'http://%s:%d' % (lan_ip(), PORT)


def hub_extra_public_url():
    """本机的公网入口：手动指定的 HLJJS_PUBLIC_URL 或正在跑的云隧道地址。"""
    for u in ((HUB_PUBLIC_URL if 'HUB_PUBLIC_URL' in globals() else ''), PUBLIC_URL):
        try:
            u = str(u or '').strip().rstrip('/')
        except Exception:
            u = ''
        if u.startswith('http://') or u.startswith('https://'):
            return u
    return ''


def hub_admin_ok(h):
    try:
        if h._loopback():
            return True
    except Exception:
        pass
    if not HUB_ADMIN_TOKEN:
        return False
    try:
        got = h.headers.get('X-Hub-Admin') or ''
    except Exception:
        got = ''
    return hmac.compare_digest(str(got), HUB_ADMIN_TOKEN)


def tts_guest_ok(h):
    """TTS / 转码端点鉴权：本机来源或带正确的 X-Hub-Token（= HUB_ADMIN_TOKEN）。

    注意：cloudflared 隧道转发进来的请求 socket 源地址是 127.0.0.1，
    不能直接用 _loopback() 判断，必须用 _real_ip() 读 Cf-Connecting-IP / X-Real-IP。
    """
    try:
        ip = _real_ip(h)
    except Exception:
        ip = ''
    if ip in ('127.0.0.1', '::1', '::ffff:127.0.0.1', 'localhost'):
        return True
    if not HUB_ADMIN_TOKEN:
        return False
    try:
        got = h.headers.get('X-Hub-Token') or h.headers.get('X-Hub-Admin') or ''
    except Exception:
        got = ''
    return hmac.compare_digest(str(got), HUB_ADMIN_TOKEN)


def hub_link_info():
    cfg = hub_cfg()
    with ROOM_LOCK:
        members = sum(len(r['members']) for r in ROOMS.values())
    online = sum(1 for r in hub_conns_merged() if r.get('online'))
    out = {'id': cfg.get('id') or '', 'name': cfg.get('name') or '',
           'url': hub_public_url(), 'pubUrl': hub_extra_public_url(), 'open': hub_open(),
           'door': hub_door_open(), 'members': members, 'online': online}
    out.update(hub_identity())
    return out


def hub_http_json(url, payload=None, timeout=5):
    import urllib.request
    data = None
    headers = {}
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode('utf-8')
        headers['Content-Type'] = 'application/json'
    req = urllib.request.Request(url, data=data, headers=headers,
                                 method='POST' if data is not None else 'GET')
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode('utf-8'))


def hub_peer_add(pid, url, name, key, verified=False, fp='', pub=None, error='', pubUrl=''):
    pid = str(pid or '')[:64]
    url = str(url or '').strip().rstrip('/')[:300]
    if not pid or not (url.startswith('http://') or url.startswith('https://')):
        return False
    with HUB_PEER_LOCK:
        old = HUB_PEERS.get(pid) or {}
        if old.get('fp') and fp and old.get('fp') != fp:
            try:
                print('hub peer key changed:', pid, old.get('fp'), '->', fp)
            except Exception:
                pass
        HUB_PEERS[pid] = {'id': pid, 'url': url, 'name': str(name or '')[:24],
                          'key': str(key or '')[:32], 'since': old.get('since') or time.time(),
                          'pubUrl': str(pubUrl or old.get('pubUrl') or '')[:300],
                          'verified': bool(verified), 'fp': str(fp or old.get('fp') or '')[:16],
                          'pub': pub or old.get('pub'), 'verifyError': str(error or '')[:80]}
    hub_peers_save()
    return True


def hub_peer_remove(pid, key=None):
    pid = str(pid or '')
    with HUB_PEER_LOCK:
        p = HUB_PEERS.get(pid)
        if not p:
            return None
        if key and p.get('key') and p.get('key') != key:
            return None
        HUB_PEERS.pop(pid, None)
    hub_peers_save()
    return p


def hub_peers_save():
    with HUB_PEER_LOCK:
        rows = [dict(v) for v in HUB_PEERS.values()][:200]
    _hub_write_json(HUB_PEER_FILE, rows)


hub_cfg_load()

# ---------------- 中枢间转发（消息级联邦） ----------------
FED_LOCK = threading.Lock()
FED_ACTIVE = {}          # pair -> 时间戳（正=桥已建立，负=正在发起）


def hub_peers_all():
    with HUB_PEER_LOCK:
        return [dict(v) for v in HUB_PEERS.values()]


def relay_waiting(pair, exclude=None):
    """本机这个 pair 上正在等的客户端（不含跨中枢桥的虚拟连接）。"""
    with RELAY_LOCK:
        slot = RELAY.get(pair) or {}
        for k, v in slot.items():
            if getattr(v, 'virtual', False):
                continue
            if exclude is not None and v is exclude:
                continue
            return v
    return None


def hub_relay_canon(kind, pair, fromid, ts, cmid=''):
    return '%s|%s|%s|%s|%s' % (kind, pair, fromid, ts, cmid)


def hub_relay_sig(key, canon):
    return hmac.new(str(key or '').encode('utf-8'), canon.encode('utf-8'),
                    hashlib.sha256).hexdigest()[:32]


def hub_peer_urls(peer):
    """这个中枢可以尝试的地址：先用串联时记的地址，再退回它的公网入口。"""
    out = []
    for k in ('url', 'pubUrl'):
        u = str((peer or {}).get(k) or '').strip().rstrip('/')
        if (u.startswith('http://') or u.startswith('https://')) and u not in out:
            out.append(u)
    return out


def hub_peer_by_id(pid):
    for p in hub_peers_all():
        if str(p.get('id') or '') == str(pid or ''):
            return p
    return None


class FedConn:
    """跨中枢桥的虚拟对端：send() 把帧写到另一个中枢的 WS 上。"""
    def __init__(self, pair, mid, sock):
        self.code = pair
        self.id = mid
        self.name = ''
        self.sock = sock
        self.alive = True
        self.lock = threading.Lock()
        self.virtual = True
        self.rx = time.time()

    def send(self, opcode, data):
        if not self.alive:
            return False
        try:
            with self.lock:
                self.sock.sendall(_ws_pack_client(opcode, data))
            return True
        except Exception:
            self.alive = False
            return False

    def send_json(self, obj):
        return self.send(0x1, json.dumps(obj, ensure_ascii=False).encode('utf-8'))


def _fed_bridge(pair, conn, peer, remote_mid, cname, bases=None):
    """本机客户端 conn ↔ peer 中枢上的 remote_mid：连过去并双向转发。

    attach 的 cmid 必须是**本机这个客户端的 id**（对方中枢要拿它当虚拟对端登记）；
    如果传对方那个客户端的 id，虚拟连接会和它在同一个 pair 里撞 id，帧就转不动了。
    """
    from urllib.parse import quote
    try:
        me = str(hub_link_info().get('id') or '')
        ts = int(time.time())
        canon = hub_relay_canon('relay-attach', pair, me, ts, conn.id)
        qs = ('/__hub/relay/attach?pair=%s&from=%s&cmid=%s&cname=%s&ts=%s&sig=%s' % (
            quote(pair), quote(me), quote(conn.id), quote(conn.name or ''), ts,
            hub_relay_sig(peer.get('key'), canon)))
        sock = None
        rf = None
        for base in (bases or hub_peer_urls(peer)):
            try:
                sock, rf = ws_client_open(base, qs)
                break
            except Exception:
                sock = None
                continue
        if sock is None:
            raise IOError('所有地址都连不上')
    except Exception as e:
        try:
            print('fed bridge failed:', e)
        except Exception:
            pass
        return False
    fed = FedConn(pair, 'fed|' + str(peer.get('id') or ''), sock)
    with RELAY_LOCK:
        RELAY.setdefault(pair, {})[fed.id] = fed
    with FED_LOCK:
        FED_ACTIVE[pair] = time.time()
    try:
        conn.send_json({'t': 'relay-peer', 'id': remote_mid, 'name': cname or ''})
    except Exception:
        pass
    try:
        while conn.alive and fed.alive:
            op, data = _ws_recv(rf)
            if op == 0x8:
                break
            if op == 0x9:
                fed.send(0xA, data)
                continue
            if op in (0x1, 0x2):
                conn.send(op, data)
    except Exception:
        pass
    finally:
        fed.alive = False
        try:
            sock.close()
        except Exception:
            pass
        with RELAY_LOCK:
            slot = RELAY.get(pair) or {}
            if slot.get(fed.id) is fed:
                slot.pop(fed.id, None)
        with FED_LOCK:
            if FED_ACTIVE.get(pair):
                FED_ACTIVE.pop(pair, None)
        try:
            conn.send_json({'t': 'relay-peer-off', 'id': remote_mid})
        except Exception:
            pass
    return True


def _fed_try(pair, conn):
    """本机这个 pair 只有自己：问串联中枢那边有没有人在等，有就连过去桥起来。"""
    try:
        me = str(hub_link_info().get('id') or '')
        if not me or not hub_peers_all():
            return False
        with FED_LOCK:
            if FED_ACTIVE.get(pair):
                return False
            FED_ACTIVE[pair] = -time.time()

        def worker():
            try:
                for _ in range(10):
                    time.sleep(1.2)
                    if not conn.alive:
                        return
                    if relay_waiting(pair, conn) is not None:
                        return
                    for peer in hub_peers_all():
                        if not peer.get('url') or not peer.get('key'):
                            continue
                        # 只让 id 小的一侧发起：两边都发起会建两条桥、消息重复
                        if me > str(peer.get('id') or ''):
                            continue
                        ts = int(time.time())
                        canon = hub_relay_canon('relay-find', pair, me, ts)
                        bases = hub_peer_urls(peer)
                        r = None
                        for base in bases:
                            try:
                                r = hub_http_json(base + '/__hub/relay/find',
                                                  {'pair': pair, 'from': me, 'ts': ts,
                                                   'sig': hub_relay_sig(peer.get('key'), canon)},
                                                  timeout=3)
                                break
                            except Exception:
                                continue
                        if not r or not r.get('waiting') or not r.get('mid'):
                            continue
                        if _fed_bridge(pair, conn, peer, str(r['mid']), str(r.get('name') or ''),
                                       bases=bases):
                            return
            finally:
                with FED_LOCK:
                    if (FED_ACTIVE.get(pair) or 0) < 0:
                        FED_ACTIVE.pop(pair, None)
        try:
            threading.Thread(target=worker, daemon=True).start()
        except Exception:
            pass
        return True
    except Exception:
        return False


ROOM_SOCK_TIMEOUT = 90          # 客户端每 30 秒 ping 一次；90 秒没动静就认为掉线

_WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'


def _ws_accept_key(key):
    return base64.b64encode(hashlib.sha1((key + _WS_GUID).encode('utf-8')).digest()).decode('ascii')


def _ws_pack(opcode, data):
    n = len(data)
    if n < 126:
        head = bytes([0x80 | opcode, n])
    elif n < 65536:
        head = bytes([0x80 | opcode, 126]) + n.to_bytes(2, 'big')
    else:
        head = bytes([0x80 | opcode, 127]) + n.to_bytes(8, 'big')
    return head + data


def _ws_read(rf, n):
    buf = b''
    while len(buf) < n:
        chunk = rf.read(n - len(buf))
        if not chunk:
            raise EOFError('closed')
        buf += chunk
    return buf


def _ws_recv(rf):
    """读一条完整消息（自动拼接分片）。返回 (opcode, data)；对端关闭返回 (8, b'')。"""
    msg = b''
    op0 = 0
    while True:
        head = _ws_read(rf, 2)
        b1, b2 = head[0], head[1]
        fin = bool(b1 & 0x80)
        op = b1 & 0x0f
        masked = bool(b2 & 0x80)
        n = b2 & 0x7f
        if n == 126:
            n = int.from_bytes(_ws_read(rf, 2), 'big')
        elif n == 127:
            n = int.from_bytes(_ws_read(rf, 8), 'big')
        if n > ROOM_MAX_FRAME:
            raise ValueError('frame too big')
        mask = _ws_read(rf, 4) if masked else b''
        data = _ws_read(rf, n) if n else b''
        if masked and data:
            data = bytes(c ^ mask[i & 3] for i, c in enumerate(data))
        if op == 0x8:
            return (0x8, b'')
        if op == 0x9:
            return (0x9, data)
        if op == 0xA:
            continue
        if op != 0:
            op0 = op
        msg += data
        if fin:
            return (op0 or 0x1, msg)


def _ws_pack_client(opcode, data):
    """客户端→服务端的帧必须带掩码（RFC 6455）。"""
    n = len(data)
    mask = os.urandom(4)
    if n < 126:
        head = bytes([0x80 | opcode, 0x80 | n])
    elif n < 65536:
        head = bytes([0x80 | opcode, 0x80 | 126]) + n.to_bytes(2, 'big')
    else:
        head = bytes([0x80 | opcode, 0x80 | 127]) + n.to_bytes(8, 'big')
    return head + mask + bytes(c ^ mask[i & 3] for i, c in enumerate(data))


class _PreBuf:
    """把握手时多读出来的字节接回去，给 _ws_recv 当文件用。"""
    def __init__(self, rf, pre=b''):
        self.rf = rf
        self.pre = pre

    def read(self, n):
        if self.pre:
            out, self.pre = self.pre[:n], self.pre[n:]
            if len(out) == n:
                return out
            rest = self.rf.read(n - len(out))
            return out + (rest or b'')
        return self.rf.read(n)


def ws_client_open(base, path_qs, timeout=6):
    """当 WebSocket 客户端去连另一个中枢（中枢间转发用）。返回 (sock, 读文件)。"""
    from urllib.parse import urlparse
    u = urlparse(base)
    host = u.hostname or ''
    port = u.port or (443 if u.scheme == 'https' else 80)
    sock = socket.create_connection((host, port), timeout=timeout)
    if u.scheme == 'https':
        import ssl
        sock = ssl.create_default_context().wrap_socket(sock, server_hostname=host)
    key = base64.b64encode(os.urandom(16)).decode('ascii')
    req = ('GET %s HTTP/1.1\r\nHost: %s:%d\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
           'Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n') % (path_qs, host, port, key)
    sock.sendall(req.encode('ascii'))
    raw = sock.makefile('rb')
    head = b''
    while b'\r\n\r\n' not in head:
        ch = raw.read(1)
        if not ch:
            raise IOError('ws handshake closed')
        head += ch
        if len(head) > 16384:
            raise IOError('ws handshake too big')
    if b'101' not in head.split(b'\r\n')[0]:
        raise IOError('ws upgrade rejected')
    rest = head.split(b'\r\n\r\n', 1)[1]
    return sock, _PreBuf(raw, rest)


class RoomConn:
    def __init__(self, handler, code, mid, name):
        self.h = handler
        self.code = code
        self.id = mid
        self.name = name
        self.lock = threading.Lock()
        self.alive = True
        self.rx = 0
        self.admin = False      # 团长（带 token 连进来的那个）
        self.muted = False      # 被团长禁言
        self.in_room = True     # 是否"在社团里"（presence）
        self.bytes = 0          # 这个成员往社团里发了多少字节（配额用）
        self.joined = time.time()
        self.alias = ''
        self.vice = False
        self.member_rate = 0
        self.tokens = 0.0       # 限速令牌桶
        self.tok_ts = time.time()
        self.tok_ready = False
        self.quota_warned = False

    def send(self, opcode, data):
        if not self.alive:
            return False
        try:
            with self.lock:
                self.h.wfile.write(_ws_pack(opcode, data))
            return True
        except Exception:
            self.alive = False
            return False

    def send_json(self, obj):
        return self.send(0x1, json.dumps(obj, ensure_ascii=False).encode('utf-8'))

    def kick(self, reason):
        # 先发再关：如果先置 alive=False，send() 会直接返回，被踢的人就只看到一条"连接断了"
        try:
            self.send_json({'t': 'kicked', 'why': reason})
        except Exception:
            pass
        try:
            self.send(0x8, b'')
        except Exception:
            pass
        self.alive = False
        try:
            self.h.connection.shutdown(socket.SHUT_RDWR)
        except Exception:
            pass

    def close_socket(self, reason=''):
        # 中枢开关用：只断开，不置 kicked（重开之后还能重连）
        try:
            self.send_json({'t': 'hub-closed', 'why': reason or '中枢已关闭'})
        except Exception:
            pass
        try:
            self.h.close_connection = True
            if self.h.connection is not None:
                self.h.connection.shutdown(socket.SHUT_RDWR)
        except Exception:
            pass


def _mb_text(n):
    try:
        n = int(n)
    except Exception:
        return '?'
    if n >= 1024 * 1024 * 1024:
        return '%.1f GB' % (n / 1073741824.0)
    if n >= 1024 * 1024:
        return '%.0f MB' % (n / 1048576.0)
    if n >= 1024:
        return '%.0f KB' % (n / 1024.0)
    return '%d B' % n


def hub_members_total():
    with ROOM_LOCK:
        return sum(len(r['members']) for r in ROOMS.values())


def hub_drop_all(reason):
    targets = []
    with ROOM_LOCK:
        for r in ROOMS.values():
            targets.extend(list(r['members'].values()))
    for c in targets:
        try:
            c.close_socket(reason)
        except Exception:
            pass


def hub_relay_drop_all(reason):
    """关中枢时把一对一兜底中继的 WebSocket 也真断掉（否则要等 90 秒超时）。"""
    with RELAY_LOCK:
        conns = []
        for slot in RELAY.values():
            conns.extend(list(slot.values()))
    for c in conns:
        try:
            c.close_socket(reason)
        except Exception:
            pass


def _member_ip(m):
    try:
        xff = m.h.headers.get('X-Forwarded-For') or ''
        if xff:
            return xff.split(',')[0].strip()[:64]
    except Exception:
        pass
    try:
        ip = str(m.h.client_address[0] or '')
        if ip.startswith('::ffff:'):
            ip = ip[7:]
        return ip[:64]
    except Exception:
        return ''


def _room_roster_msg(room):
    ms = []
    for m in room['members'].values():
        ms.append({
            'id': m.id, 'name': (m.alias or m.name),
            'muted': bool(m.muted), 'vice': bool(m.vice), 'admin': bool(m.admin or (room.get('admin_id') and m.id == room.get('admin_id'))),
            'bytes': int(m.bytes),
            'inRoom': bool(getattr(m, 'in_room', False)),
            'ip': _member_ip(m),
        })
    return {
        't': 'ws-roster',
        'members': ms,
        'room': {
            'code': room['code'], 'name': room['name'], 'cap': room['cap'],
            'avatar': room.get('avatar') or '',
            'notice': room.get('notice') or '',
            'noticeImgs': room.get('notice_imgs') or [],
            'fileMax': int(room.get('file_max') or 0),
            'quota': int(room.get('quota') or 0),
            'rate': int(room.get('rate') or 0),
        },
    }


def room_members_snapshot(code):
    with ROOM_LOCK:
        r = ROOMS.get(code)
        return list(r['members'].values()) if r else []


def room_broadcast(code, sender, opcode, data):
    for m in room_members_snapshot(code):
        if m is not sender:
            m.send(opcode, data)


def room_push_roster(code):
    with ROOM_LOCK:
        r = ROOMS.get(code)
        if not r:
            return
        msg = json.dumps(_room_roster_msg(r), ensure_ascii=False).encode('utf-8')
        targets = list(r['members'].values())
    for m in targets:
        m.send(0x1, msg)


def _open_gc_locked():
    now = time.time()
    for k, r in list(OPEN_ROOMS.items()):
        if now - r['ts'] > OPEN_TTL:
            OPEN_ROOMS.pop(k, None)


def room_gc():
    now = time.time()
    with ROOM_LOCK:
        for code, r in list(ROOMS.items()):
            empty = not r['members']
            if (empty and now - r['at'] > ROOM_MAX_IDLE) or (now - r['at'] > ROOM_MAX_LIFE):
                ROOMS.pop(code, None)


def room_roster(code):
    with ROOM_LOCK:
        r = ROOMS.get(code)
        return _room_roster_msg(r) if r else {'t': 'ws-roster', 'members': []}


def room_admin(code, conn, m):
    """团长的管理指令：禁言 / 踢出 / 封为副团长 / 封禁 / 单人限速 / 改名 / 要一份成员表。"""
    act = str(m.get('act') or '')
    tid = str(m.get('id') or '')
    target = None
    need_push = False
    kick_reason = ''
    with ROOM_LOCK:
        r = ROOMS.get(code)
        if not r:
            return
        target = r['members'].get(tid)
        if act == 'vice' and target is not None:
            target.vice = bool(m.get('on'))
            need_push = True
        elif act == 'ban' and target is not None:
            r.setdefault('banned', {})[tid] = 0
            r['members'].pop(tid, None)
            kick_reason = '你被封禁了，这个社团进不来'
            need_push = True
        elif act == 'rate' and target is not None:
            try:
                target.member_rate = max(0, int(float(m.get('rate') or 0) * 1024))  # 客户端给的是 KB/s
            except Exception:
                target.member_rate = 0
        elif act == 'rename' and target is not None:
            target.alias = str(m.get('name') or '')[:24]
            need_push = True
        elif act == 'mute' and target is not None:
            target.muted = bool(m.get('on', True))
            need_push = True
        elif act == 'kick' and target is not None:
            r['members'].pop(tid, None)
            r['at'] = time.time()
            r.setdefault('banned', {})[tid] = time.time() + 10 * 60
            kick_reason = '团长把你移出了社团'
            need_push = True
    if act == 'who':
        conn.send_json(room_roster(code))
        return
    if target is None:
        return
    if act == 'mute':
        target.send_json({'t': 'muted', 'on': bool(target.muted)})
    if kick_reason:
        try:
            target.kick(kick_reason)
        except Exception:
            pass
    if need_push:
        room_push_roster(code)


ROOMS_FILE = os.path.join(HUB_DATA_DIR, 'rooms.json')


def rooms_save():
    try:
        with ROOM_LOCK:
            items = []
            for code, r in list(ROOMS.items()):
                items.append({
                    'code': code,
                    'name': str(r.get('name') or '社团')[:24],
                    'cap': int(r.get('cap') or 8),
                    'token': str(r.get('token') or ''),
                    'at': float(r.get('at') or 0),
                    'file_max': int(r.get('file_max') or 0),
                    'quota': int(r.get('quota') or 0),
                    'rate': int(r.get('rate') or 0),
                    'notice': str(r.get('notice') or ''),
                    'notice_img': str(r.get('notice_img') or ''),
                    'notice_imgs': list(r.get('notice_imgs') or []),
                    'avatar': str(r.get('avatar') or ''),
                    'admin_id': r.get('admin_id'),
                    'open': code in OPEN_ROOMS,
                })
        _hub_data_ensure()
        _hub_write_json(ROOMS_FILE, items)
    except Exception:
        pass


def rooms_load():
    try:
        rows = _hub_read_json(ROOMS_FILE, [])
    except Exception:
        return
    if not isinstance(rows, list):
        return
    for r in rows[:500]:
        if not isinstance(r, dict) or not r.get('code') or not r.get('token'):
            continue
        code = str(r['code'])[:16]
        with ROOM_LOCK:
            ROOMS[code] = {
                'code': code,
                'name': str(r.get('name') or '社团')[:24],
                'cap': max(2, min(30, int(r.get('cap') or 8))),
                'token': str(r['token'])[:32],
                'at': float(r.get('at') or time.time()),
                'members': {},
                'file_max': int(r.get('file_max') or 0),
                'quota': int(r.get('quota') or 0),
                'rate': int(r.get('rate') or 0),
                'notice': str(r.get('notice') or ''),
                'notice_img': str(r.get('notice_img') or ''),
                'notice_imgs': list(r.get('notice_imgs') or []),
                'avatar': str(r.get('avatar') or ''),
                'admin_id': r.get('admin_id'),
            }
        if r.get('open'):
            with OPEN_LOCK:
                OPEN_ROOMS[code] = {'code': code, 'name': str(r.get('name') or '社团')[:24],
                                    'cap': max(2, min(30, int(r.get('cap') or 8))),
                                    'invite': '', 'kind': 'ws', 'private': False,
                                    'token': str(r['token'])[:32], 'answers': [], 'ts': time.time()}


def room_new(name, cap, file_max=0, quota=0, rate=0):
    room_gc()
    with ROOM_LOCK:
        used = set(ROOMS.keys())
        code = ''
        for _ in range(500):
            c = '%04d' % random.randint(0, 9999)
            if c not in used:
                code = c
                break
        if not code:
            raise RuntimeError('社团太多')
        token = hashlib.sha1(os.urandom(16)).hexdigest()[:16]
        ROOMS[code] = {
            'code': code, 'name': name, 'cap': cap, 'token': token,
            'at': time.time(), 'members': {},
            'file_max': int(file_max or 0), 'quota': int(quota or 0), 'rate': int(rate or 0),
            'notice': '', 'notice_img': '', 'notice_imgs': [], 'avatar': '', 'admin_id': None,
        }
    return code, token


class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = dict(http.server.SimpleHTTPRequestHandler.extensions_map)
    extensions_map.update({
        '.webmanifest': 'application/manifest+json',
        '.js': 'text/javascript; charset=utf-8',
        '.html': 'text/html; charset=utf-8',
        '.json': 'application/json; charset=utf-8',
        '.png': 'image/png',
        '.apk': 'application/vnd.android.package-archive',
    })

    def setup(self):
        super().setup()
        try:
            _base = self.wfile
            class _CountedOut:
                def __init__(self, f):
                    self._f = f
                def write(self, b):
                    try:
                        _usage_add('out', len(b))
                    except Exception:
                        pass
                    return self._f.write(b)
                def writelines(self, ls):
                    for x in ls:
                        self.write(x)
                def flush(self):
                    try:
                        return self._f.flush()
                    except Exception:
                        pass
                def __getattr__(self, n):
                    return getattr(self._f, n)
            self.wfile = _CountedOut(_base)
        except Exception:
            pass

    def __init__(self, *a, **kw):
        super().__init__(*a, directory=ROOT, **kw)

    def log_request(self, code='-', size='-'):
        try:
            with USAGE_LOCK:
                USAGE['req'] += 1
        except Exception:
            pass

    def log_message(self, fmt, *args):
        pass

    # ---------- 小工具 ----------
    def _cors(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Headers', 'content-type, x-hub-admin')
        self.send_header('Access-Control-Allow-Methods', 'GET,POST,OPTIONS')
        self.send_header('Cache-Control', 'no-store')

    def _loopback(self):
        """USB 反向 / 本机页面 = 127.0.0.1；反向代理和同一 Wi-Fi 的其他人不是。"""
        try:
            # Caddy/Nginx 反代会从 127.0.0.1 连进来；不能因此把公网用户当本机管理员
            if (self.headers.get('X-Forwarded-For') or self.headers.get('X-Real-IP')
                    or self.headers.get('Forwarded')):
                return False
        except Exception:
            pass
        try:
            ip = str(self.client_address[0] or '')
        except Exception:
            ip = ''
        return ip in ('127.0.0.1', '::1') or ip.startswith('127.') or ip.startswith('::ffff:127.')

    def _json(self, obj, code=200):
        try:
            if code in (400, 401, 403, 404):
                _guard_note(_real_ip(self), getattr(self, "path", ""), code)
        except Exception:
            pass
        body = json.dumps(obj, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self._cors()
        self.end_headers()
        self.wfile.write(body)

    def _body(self):
        try:
            n = int(self.headers.get('Content-Length') or 0)
            raw = self.rfile.read(n) if n > 0 else b''
            _usage_add('in', len(raw))
            return json.loads(raw.decode('utf-8') or '{}')
        except Exception:
            return {}

    def _query(self):
        q = {}
        if '?' in self.path:
            for part in self.path.split('?', 1)[1].split('&'):
                if '=' in part:
                    k, v = part.split('=', 1)
                    q[k] = v
        return q

    def end_headers(self):
        path = self.path.split('?')[0]
        if path.endswith('sw.js') or path.endswith('.webmanifest') or path.startswith('/__'):
            self.send_header('Cache-Control', 'no-cache, no-store, must-revalidate')
        # 全站跨源隔离：PPSSPP 需要 SharedArrayBuffer，而 iframe 只有在
        # 父页面也是隔离状态时才拿得到。
        # 只给 PPSSPP 资源加跨源隔离：全局开 COEP 会拦掉内置浏览器里
        # 外部网站的图片/脚本（NotSameOriginAfterDefaultedToSameOriginByCoep）
        if path.startswith('/plugins/ppsspp'):
            self.send_header('Cross-Origin-Opener-Policy', 'same-origin')
            self.send_header('Cross-Origin-Embedder-Policy', 'require-corp')
            self.send_header('Cross-Origin-Resource-Policy', 'cross-origin')
        self.send_header('Service-Worker-Allowed', '/')
        super().end_headers()

    # ---------- 社团中枢（WebSocket） ----------
    def _room_info(self):
        if not hub_door_open():
            return self._json({'ok': False, 'door': True, 'error': hub_door_error()}, 403)
        from urllib.parse import unquote
        q = self._query()
        code = unquote(str(q.get('code') or '')).strip()
        with ROOM_LOCK:
            r = ROOMS.get(code)
            if not r:
                return self._json({'ok': False, 'error': '社团不存在或已过期'}, 404)
            return self._json({'ok': True, 'code': r['code'], 'name': r['name'], 'cap': r['cap'],
                               'notice': r.get('notice') or '', 'noticeImg': r.get('notice_img') or '',
                               'noticeImgs': r.get('notice_imgs') or [],
                               'avatar': r.get('avatar') or '',
                               'members': len(r['members']),
                               'names': [m.name for m in r['members'].values()]})

    def _room_new(self):
        if not hub_open():
            return self._json({'ok': False, 'error': '中枢已关闭'}, 503)
        cfg = hub_cfg()
        if cfg['maxRooms']:
            with ROOM_LOCK:
                if len(ROOMS) >= cfg['maxRooms']:
                    return self._json({'ok': False, 'error': '中枢房间数到上限（%d）' % cfg['maxRooms']}, 503)
        body = self._body() or {}
        name = str(body.get('name') or '社团')[:24]
        try:
            cap = int(body.get('cap') or 8)
        except Exception:
            cap = 8
        cap = max(2, min(cfg['maxPerRoom'] or 30, min(30, cap)))

        def _mb(v):
            try:
                return max(0, int(float(v or 0) * 1048576.0))
            except Exception:
                return 0

        def _bps(v):
            try:
                return max(0, int(float(v or 0) * 1024.0))
            except Exception:
                return 0

        file_max = _mb(body.get('fileMaxMB'))
        quota = _mb(body.get('quotaMB'))
        rate = _bps(body.get('rateKBps'))
        try:
            code, token = room_new(name, cap, file_max, quota, rate)
        except Exception as e:
            return self._json({'ok': False, 'error': str(e)}, 500)
        # 开门社团建好就默认登记到目录，直接能被搜到（不用再进房间点一次「开门」）
        try:
            with OPEN_LOCK:
                _open_gc_locked()
                OPEN_ROOMS.pop(code, None)
                OPEN_ROOMS[code] = {'code': code, 'name': name, 'cap': cap, 'invite': '',
                                    'kind': 'ws', 'private': False, 'token': token,
                                    'answers': [], 'ts': time.time()}
        except Exception:
            pass
        rooms_save()
        return self._json({'ok': True, 'code': code, 'token': token, 'cap': cap,
                           'fileMax': file_max, 'quota': quota, 'rate': rate,
                           'url': hub_public_url() + '/'})

    def _room_list(self):
        if not hub_open():
            return self._json({'ok': True, 'rooms': []})
        room_gc()
        # 只列开放的社团：关门后代码不该再出现在任何列表里
        with OPEN_LOCK:
            _open_gc_locked()
            open_codes = set(k for k, v in OPEN_ROOMS.items() if not v.get('private'))
        with ROOM_LOCK:
            rooms = [r for r in ROOMS.values() if r['code'] in open_codes]
            out = [{
                'code': r['code'], 'name': r['name'], 'cap': r['cap'],
                'members': len(r['members']),
                'fileMax': int(r.get('file_max') or 0),
                'quota': int(r.get('quota') or 0),
                'rate': int(r.get('rate') or 0),
                'notice': r.get('notice') or '',
                'hasImg': bool(r.get('notice_img')),
            } for r in rooms]
        return self._json({'ok': True, 'rooms': out[:50], 'count': len(out)})

    def _room_delete(self):
        body = self._body() or {}
        code = str(body.get('code') or '').strip()
        token = str(body.get('token') or '').strip()
        members = []
        with ROOM_LOCK:
            r = ROOMS.get(code)
            if not r:
                return self._json({'ok': False, 'error': '社团不存在或已过期'}, 404)
            if token != r['token']:
                return self._json({'ok': False, 'error': '只有团长能解散'}, 403)
            members = list(r['members'].values())
            ROOMS.pop(code, None)
        with OPEN_LOCK:
            OPEN_ROOMS.pop(code, None)
        rooms_save()
        for m in members:
            try:
                m.kick('团长解散了社团')
            except Exception:
                pass
        return self._json({'ok': True})

    def _room_config(self):
        body = self._body() or {}
        code = str(body.get('code') or '').strip()
        token = str(body.get('token') or '').strip()
        cfg = hub_cfg()

        def _mb(v):
            try:
                return max(0, int(float(v or 0) * 1048576.0))
            except Exception:
                return 0

        with ROOM_LOCK:
            r = ROOMS.get(code)
            if not r:
                return self._json({'ok': False, 'error': '社团不存在或已过期'}, 404)
            if token != r['token']:
                return self._json({'ok': False, 'error': '只有团长能改设置'}, 403)
            if body.get('cap') is not None:
                try:
                    r['cap'] = max(2, min(cfg['maxPerRoom'] or 30, int(body.get('cap'))))
                except Exception:
                    pass
            if body.get('fileMaxMB') is not None:
                r['file_max'] = _mb(body.get('fileMaxMB'))
            if body.get('quotaMB') is not None:
                r['quota'] = _mb(body.get('quotaMB'))
            if body.get('name') is not None:
                nm = str(body.get('name') or '').strip()[:24]
                if nm: r['name'] = nm
            if body.get('avatar') is not None:
                av = str(body.get('avatar') or '')
                if av and (not av.startswith('data:image/') or len(av) > 24000):
                    return self._json({'ok': False, 'error': '头像太大'}, 413)
                r['avatar'] = av
            if body.get('noticeImg') is not None:
                img = str(body.get('noticeImg') or '')
                if img and (not img.startswith('data:image/') or len(img) > 60000):
                    return self._json({'ok': False, 'error': '徽章太大（压缩后要小于 40KB）'}, 413)
                r['notice_img'] = img
            if body.get('noticeImgs') is not None:
                arr = body.get('noticeImgs')
                if not isinstance(arr, list):
                    return self._json({'ok': False, 'error': '公告图片格式不对'}, 400)
                imgs = []
                for x in arr[:3]:
                    u = str(x or '')
                    if not u.startswith('data:image/'):
                        continue
                    if len(u) > 250000:
                        return self._json({'ok': False, 'error': '公告图片太大（单张要小于 250KB）'}, 413)
                    imgs.append(u)
                r['notice_imgs'] = imgs
            if body.get('notice') is not None:
                r['notice'] = str(body.get('notice') or '')[:1000]
            if body.get('rateKBps') is not None:
                try:
                    r['rate'] = max(0, int(float(body.get('rateKBps') or 0) * 1024.0))
                except Exception:
                    r['rate'] = 0
            r['at'] = time.time()
            out = {'cap': r['cap'], 'fileMax': r['file_max'], 'quota': r['quota'], 'rate': r['rate'],
                   'notice': r['notice'], 'noticeImg': r['notice_img'],
                   'noticeImgs': r.get('notice_imgs') or [], 'name': r['name'], 'avatar': r.get('avatar') or ''}
        room_push_roster(code)
        rooms_save()
        return self._json({'ok': True, 'room': out})

    def _room_transfer(self):
        """团长转让：把 admin 换给另一个人，同时换 token（老团长手里的 token 立刻失效）"""
        body = self._body() or {}
        code = str(body.get('code') or '').strip()
        token = str(body.get('token') or '').strip()
        target = str(body.get('target') or '').strip()
        new_tok = hashlib.sha1(os.urandom(16)).hexdigest()[:16]
        with ROOM_LOCK:
            r = ROOMS.get(code)
            if not r:
                return self._json({'ok': False, 'error': '社团不存在或已过期'}, 404)
            if token != r['token']:
                return self._json({'ok': False, 'error': '只有团长能转让'}, 403)
            t = r['members'].get(target)
            if not t:
                return self._json({'ok': False, 'error': '对方不在社团里'}, 404)
            r['token'] = new_tok
            r['admin_id'] = target
            for m in r['members'].values():
                m.admin = (m.id == target)
            r['at'] = time.time()
        try:
            t.send_json({'t': 'host-token', 'token': new_tok})
        except Exception:
            pass
        room_push_roster(code)
        rooms_save()
        return self._json({'ok': True, 'token': new_tok})

    # ---------- 开放社团（信箱模式：只帮牵线，不过数据） ----------
    def _lan_open(self):
        if not hub_open():
            return self._json({'ok': False, 'error': '中枢已关闭'}, 503)
        cfg = hub_cfg()
        body = self._body() or {}
        kind = str(body.get('kind') or 'p2p')
        invite = str(body.get('invite') or '')
        fixed = str(body.get('code') or '').strip()
        private = bool(body.get('private'))
        if kind == 'ws':
            if not fixed:
                return self._json({'ok': False, 'error': '缺少社团号'}, 400)
        elif not invite or len(invite) > 20000:
            return self._json({'ok': False, 'error': '邀请码不对'}, 400)
        name = str(body.get('name') or '社团')[:24]
        try:
            cap = max(2, min(cfg['maxPerRoom'] or 30, int(body.get('cap') or 8)))
        except Exception:
            cap = 8
        with OPEN_LOCK:
            _open_gc_locked()
            used = set(OPEN_ROOMS.keys())
            code = ''
            token = str(body.get('token') or '').strip()
            if kind == 'p2p' and private and fixed:
                if not (len(fixed) == 4 and fixed.isdigit()):
                    return self._json({'ok': False, 'error': '闭门密码要 4 位数字'}, 400)
                code = fixed
                old = OPEN_ROOMS.get(code)
                if old:
                    if not old.get('private') or old.get('token') != token:
                        return self._json({'ok': False, 'error': '这个四位密码已被占用'}, 409)
                    old['invite'] = invite
                    old['name'] = name
                    old['cap'] = cap
                    old['ts'] = time.time()
                    return self._json({'ok': True, 'code': code, 'token': token})
            elif kind == 'ws' and fixed:
                code = fixed
                if code in used:
                    return self._json({'ok': False, 'error': '这个社团号已被占用'}, 409)
            else:
                for _ in range(200):
                    c = '%04d' % random.randint(0, 9999)
                    if c not in used:
                        code = c
                        break
            if not code:
                return self._json({'ok': False, 'error': '社团太多了'}, 500)
            if not token:
                token = hashlib.sha1(os.urandom(16)).hexdigest()[:16]
            OPEN_ROOMS[code] = {'code': code, 'name': name, 'cap': cap, 'invite': invite, 'kind': kind,
                                'private': bool(private), 'token': token, 'answers': [], 'ts': time.time()}
        rooms_save()
        return self._json({'ok': True, 'code': code, 'token': token})

    def _hubs(self):
        """中枢目录：hubs.json + 本机已同意串联的中枢；只公开列表，不转发。"""
        try:
            with open(HUBS_FILE, 'r', encoding='utf-8') as f:
                j = json.load(f)
        except Exception:
            j = {}
        if isinstance(j, list):
            hubs, name = j, ''
        elif isinstance(j, dict):
            hubs, name = (j.get('hubs') or []), str(j.get('name') or '')[:40]
        else:
            hubs, name = [], ''
        if not isinstance(hubs, list):
            hubs = []
        out = []
        index = {}

        def _add(url, nm, pid='', linked=False):
            u = str(url or '').strip().rstrip('/')[:300]
            if not u:
                return
            if u in index:
                e = out[index[u]]
                if pid:
                    e['id'] = str(pid)[:64]
                if linked:
                    e['linked'] = True
                if not e.get('name') and nm:
                    e['name'] = str(nm)[:40]
                return
            index[u] = len(out)
            out.append({'url': u, 'name': str(nm or '')[:40],
                        'id': str(pid or '')[:64], 'linked': bool(linked)})

        for h in hubs[:200]:
            if isinstance(h, str):
                _add(h, '')
            elif isinstance(h, dict) and h.get('url'):
                _add(h.get('url'), h.get('name'))
        with HUB_PEER_LOCK:
            peers = [dict(v) for v in HUB_PEERS.values()]
        for p in peers:
            _add(p.get('url'), p.get('name'), p.get('id'), True)
        return self._json({'ok': True, 'name': name, 'hubs': out[:200],
                           'loopback': self._loopback()})

    def _hub_state(self):
        cfg = hub_cfg()
        with ROOM_LOCK:
            rooms = len(ROOMS)
            members = sum(len(r['members']) for r in ROOMS.values())
        with OPEN_LOCK:
            _open_gc_locked()
            open_rooms = len(OPEN_ROOMS)
        rows = hub_conns_merged()
        online = sum(1 for r in rows if r.get('online'))
        return self._json({'ok': True, 'guardTrip': float(HUB_STATE.get('guard_trip') or 0), 'open': hub_open(), 'door': hub_door_open(),
                           'loopback': self._loopback(), 'admin': hub_admin_ok(self),
                           'adminEnabled': bool(HUB_ADMIN_TOKEN), 'name': cfg.get('name') or '',
                           'rooms': rooms, 'members': members, 'openRooms': open_rooms,
                           'online': online, 'conns': len(rows),
                           'devices': len(hub_device_rows(rows)),
                           'clients': sum(1 for r in rows if r.get('client')),
                           'rate_limit': bool(cfg.get('rate_limit', True)),
                           'lan': hub_public_url(), 'port': PORT, 'cfg': cfg})

    def _hub_public(self, action):
        if action == 'state':
            d = dict(public_tunnel_state())
            d['ok'] = True
            return self._json(d)
        if not hub_admin_ok(self):
            return self._json({'ok': False, 'error': '只有本机管理员能开公网通道'}, 403)
        if action == 'start':
            ok, url = public_tunnel_start()
            return self._json({'ok': ok, 'url': url if ok else '', 'msg': url})
        public_tunnel_stop()
        return self._json({'ok': True, 'url': ''})

    def _hub_ratelimit(self):
        if not hub_admin_ok(self):
            return self._json({'ok': False, 'error': '只有中枢本机能改限流'}, 403)
        body = self._body() or {}
        if body.get('on') is not None:
            with HUB_LOCK:
                HUB_CFG['rate_limit'] = bool(body.get('on'))
            try:
                _hub_write_json(HUB_CFG_FILE, dict(HUB_CFG))
            except Exception:
                pass
        with HUB_LOCK:
            on = bool(HUB_CFG.get('rate_limit', True))
        return self._json({'ok': True, 'rate_limit': on})

    def _hub_set(self):
        if not hub_admin_ok(self):
            return self._json({'ok': False, 'error': '只有中枢本机能开关中枢'}, 403)
        body = self._body() or {}
        want = bool(body.get('open'))
        hub_set_open(want)
        if not want:
            hub_drop_all('中枢已关闭')
            hub_relay_drop_all('中枢已关闭')
            # 关闭后立刻清零在线统计（否则 presence 要等 75 秒 TTL，conns 也可能残留 online=True）
            with HUB_PRESENCE_LOCK:
                HUB_PRESENCE.clear()
            with HUB_CONN_LOCK:
                for _r in HUB_CONNS.values():
                    _r['online'] = False
            hub_conns_save()
        else:
            with OPEN_LOCK:
                now = time.time()
                for r in OPEN_ROOMS.values():
                    r['ts'] = now
        return self._hub_state()

    def _hub_config_post(self):
        if not hub_admin_ok(self):
            return self._json({'ok': False, 'error': '只有中枢本机能改上限'}, 403)
        body = self._body() or {}
        cfg = hub_cfg()
        if body.get('name') is not None:
            cfg['name'] = str(body.get('name') or '').strip()[:24]

        def _int(k, lo, hi, cur):
            try:
                v = int(body.get(k))
            except Exception:
                return cur
            return max(lo, min(hi, v))

        cfg['maxRooms'] = _int('maxRooms', 0, 5000, cfg.get('maxRooms', 0))
        cfg['maxMembers'] = _int('maxMembers', 0, 100000, cfg.get('maxMembers', 0))
        cfg['maxPerRoom'] = _int('maxPerRoom', 2, 30, cfg.get('maxPerRoom', 30))
        hub_cfg_save(cfg)
        return self._json({'ok': True, 'cfg': cfg})

    def _hub_door(self):
        if not hub_admin_ok(self):
            return self._json({'ok': False, 'error': '只有中枢本机能开关门'}, 403)
        body = self._body() or {}
        hub_set_door(bool(body.get('open')))
        return self._hub_state()

    def _hub_conns_get(self):
        if not hub_admin_ok(self):
            return self._json({'ok': False, 'error': '只有中枢本机能看连接记录'}, 403)
        rows = hub_conns_merged()
        return self._json({'ok': True, 'conns': rows,
                           'online': sum(1 for r in rows if r.get('online')),
                           'clients': sum(1 for r in rows if r.get('client')),
                           'total': len(rows)})

    def _hub_presence(self):
        if not hub_open():
            return self._json({'ok': False, 'error': '中枢已关闭'}, 503)
        body = self._body() or {}
        mid = str(body.get('id') or '').strip()[:64]
        if not mid:
            return self._json({'ok': False, 'error': '缺少 id'}, 400)
        online = body.get('online', True) is not False
        try:
            ip = self._real_ip()
        except Exception:
            ip = self.client_address[0] if self.client_address else ''
        hub_presence_touch(mid, str(body.get('name') or ''), online, ip)
        rows = hub_conns_merged()
        return self._json({'ok': True, 'online': sum(1 for r in rows if r.get('online')),
                           'clients': sum(1 for r in rows if r.get('client'))})

    def _hub_conns_post(self):
        if not hub_admin_ok(self):
            return self._json({'ok': False, 'error': '只有中枢本机能管理连接'}, 403)
        body = self._body() or {}
        act = str(body.get('action') or '')
        mid = str(body.get('id') or '').strip()[:64]
        if not mid or act not in ('delete', 'ban', 'unban'):
            return self._json({'ok': False, 'error': '参数不对'}, 400)
        with HUB_PRESENCE_LOCK:
            HUB_PRESENCE.pop(mid, None)
        if act == 'ban':
            with HUB_CONN_LOCK:
                HUB_BANS.add(mid)
            hub_bans_save()
            hub_drop_id(mid, '你已被中枢封禁')
            with HUB_CONN_LOCK:
                rec = HUB_CONNS.get(mid)
                if not rec:
                    rec = {'id': mid, 'name': '', 'first': time.time(), 'last': time.time(), 'online': False}
                    HUB_CONNS[mid] = rec
                rec['banned'] = True
                rec['online'] = False
                rec['last'] = time.time()
            hub_conns_save()
        elif act == 'unban':
            with HUB_CONN_LOCK:
                HUB_BANS.discard(mid)
                rec = HUB_CONNS.get(mid)
                if rec is not None:
                    rec['banned'] = False
            hub_bans_save()
            hub_conns_save()
        else:
            hub_drop_id(mid, '中枢管理员断开了这条连接')
            with HUB_CONN_LOCK:
                HUB_CONNS.pop(mid, None)
            hub_conns_save()
        return self._hub_conns_get()

    def _hub_link_info_get(self):
        return self._json(dict({'ok': True}, **hub_link_info()))

    def _hub_link_request(self):
        body = self._body() or {}
        rid = str(body.get('reqId') or '')[:32]
        fid = str(body.get('fromId') or '')[:64]
        url = str(body.get('fromUrl') or '').strip().rstrip('/')[:300]
        name = str(body.get('fromName') or '')[:24] or '未命名中枢'
        if not rid or not fid or not (url.startswith('http://') or url.startswith('https://')):
            return self._json({'ok': False, 'error': '参数不对'}, 400)
        if fid == (hub_cfg().get('id') or ''):
            return self._json({'ok': False, 'error': '不能串联自己'}, 400)
        ok, err, extra = hub_link_verify_request(body)
        if not ok:
            return self._json({'ok': False, 'error': err}, 400)
        with HUB_PEER_LOCK:
            HUB_LINK_REQS[rid] = {'reqId': rid, 'fromId': fid, 'fromUrl': url,
                                  'fromPubUrl': str(body.get('fromPubUrl') or '').strip().rstrip('/')[:300],
                                  'fromName': name or url, 'at': time.time(), 'status': 'pending',
                                  'verified': bool(extra.get('verified')), 'fp': extra.get('fp') or '',
                                  'pub': extra.get('pub')}
            if len(HUB_LINK_REQS) > 300:
                for k in list(HUB_LINK_REQS.keys())[:100]:
                    HUB_LINK_REQS.pop(k, None)
        return self._json({'ok': True})

    def _hub_link_pending(self):
        if not hub_admin_ok(self):
            return self._json({'ok': False, 'error': '只有中枢本机能审批'}, 403)
        with HUB_PEER_LOCK:
            rows = [dict(v) for v in HUB_LINK_REQS.values() if v.get('status') == 'pending']
        rows.sort(key=lambda r: r.get('at') or 0)
        return self._json({'ok': True, 'requests': rows})

    def _hub_link_approve(self):
        if not hub_admin_ok(self):
            return self._json({'ok': False, 'error': '只有中枢本机能审批'}, 403)
        body = self._body() or {}
        rid = str(body.get('reqId') or '')[:32]
        accept = bool(body.get('accept'))
        with HUB_PEER_LOCK:
            req = HUB_LINK_REQS.get(rid)
            if not req or req.get('status') != 'pending':
                return self._json({'ok': False, 'error': '申请不存在或已处理'}, 404)
            req['status'] = 'approved' if accept else 'rejected'
            req['key'] = hashlib.sha1(os.urandom(16)).hexdigest()[:16] if accept else ''
        if not accept:
            try:
                hub_http_json(req['fromUrl'] + '/__hub/link/reject', {'reqId': rid})
            except Exception:
                pass
            return self._json({'ok': True, 'accepted': False})
        key = req['key']
        hub_peer_add(req['fromId'], req['fromUrl'], req['fromName'], key,
                     verified=bool(req.get('verified')), fp=req.get('fp') or '', pub=req.get('pub'),
                     pubUrl=req.get('fromPubUrl') or '')
        own = hub_link_info()
        cb = False
        # 对方可能只有公网地址可达：LAN 地址回调失败就换它的公网入口再试一次
        _bases = []
        for _u in (req.get('fromUrl'), req.get('fromPubUrl')):
            _u = str(_u or '').strip().rstrip('/')
            if _u and _u not in _bases:
                _bases.append(_u)
        for _base in _bases:
            try:
                r = hub_http_json(_base + '/__hub/link/accept',
                                  {'reqId': rid, 'key': key,
                                   'peer': hub_link_signed_peer(rid, key)})
                cb = bool(r.get('ok'))
                if cb:
                    break
            except Exception:
                continue
        return self._json({'ok': True, 'accepted': True, 'callback': cb})

    def _hub_link_accept(self):
        body = self._body() or {}
        rid = str(body.get('reqId') or '')[:32]
        key = str(body.get('key') or '')[:32]
        peer = body.get('peer') or {}
        with HUB_PEER_LOCK:
            out = HUB_LINK_OUT.get(rid)
            if not out or out.get('status') != 'pending':
                return self._json({'ok': False, 'error': '没有这个待确认的申请'}, 404)
            out['status'] = 'linked'
        if not hub_peer_add(peer.get('id'), peer.get('url'), peer.get('name'), key,
                            pubUrl=peer.get('pubUrl') or '',
                            **hub_link_verify_peer(rid, key, peer)):
            return self._json({'ok': False, 'error': '对方地址不对'}, 400)
        v = hub_link_verify_peer(rid, key, peer)
        return self._json({'ok': True, 'verified': bool(v.get('verified')), 'error': v.get('error') or ''})

    def _hub_link_reject(self):
        body = self._body() or {}
        rid = str(body.get('reqId') or '')[:32]
        with HUB_PEER_LOCK:
            out = HUB_LINK_OUT.get(rid)
            if out is not None:
                out['status'] = 'rejected'
        return self._json({'ok': True})

    def _hub_link_out(self):
        if not hub_admin_ok(self):
            return self._json({'ok': False, 'error': '只有中枢本机能申请'}, 403)
        body = self._body() or {}
        url = str(body.get('url') or '').strip().rstrip('/')[:300]
        if not (url.startswith('http://') or url.startswith('https://')):
            return self._json({'ok': False, 'error': '地址要 http:// 或 https://'}, 400)
        own = hub_link_info()
        rid = hashlib.sha1(os.urandom(16)).hexdigest()[:16]
        try:
            r = hub_http_json(url + '/__hub/link/request',
                              hub_link_signed_request(rid, own['id'], own['url'], own['name']))
        except Exception as e:
            detail = ''
            try:
                detail = getattr(e, 'read', lambda: b'')().decode('utf-8', 'replace')[:300]
            except Exception:
                detail = ''
            return self._json({'ok': False, 'error': '连不上对方中枢：' + str(e)[:80] + (' ' + detail if detail else '')}, 502)
        if not r.get('ok'):
            return self._json({'ok': False, 'error': r.get('error') or '对方拒绝了申请'}, 400)
        with HUB_PEER_LOCK:
            HUB_LINK_OUT[rid] = {'reqId': rid, 'url': url, 'name': body.get('name') or url,
                                 'at': time.time(), 'status': 'pending'}
        return self._json({'ok': True, 'reqId': rid})

    def _hub_link_out_list(self):
        if not hub_admin_ok(self):
            return self._json({'ok': False, 'error': '只有中枢本机能看申请'}, 403)
        with HUB_PEER_LOCK:
            rows = [dict(v) for v in HUB_LINK_OUT.values()]
        rows.sort(key=lambda r: -(r.get('at') or 0))
        return self._json({'ok': True, 'out': rows})

    def _hub_link_list(self):
        if not hub_admin_ok(self):
            return self._json({'ok': False, 'error': '只有中枢本机能看串联列表'}, 403)
        with HUB_PEER_LOCK:
            rows = [dict(v) for v in HUB_PEERS.values()]
        rows.sort(key=lambda r: -(r.get('since') or 0))
        return self._json({'ok': True, 'peers': rows})

    def _hub_link_remove(self):
        if not hub_admin_ok(self):
            return self._json({'ok': False, 'error': '只有中枢本机能退出'}, 403)
        body = self._body() or {}
        pid = str(body.get('id') or '')[:64]
        p = hub_peer_remove(pid)
        if not p:
            return self._json({'ok': False, 'error': '没有这条串联'}, 404)
        try:
            hub_http_json(p.get('url', '') + '/__hub/link/leave',
                          {'id': (hub_cfg().get('id') or ''), 'key': p.get('key') or ''})
        except Exception:
            pass
        return self._json({'ok': True})

    def _hub_link_leave(self):
        body = self._body() or {}
        pid = str(body.get('id') or '')[:64]
        key = str(body.get('key') or '')[:32]
        p = hub_peer_remove(pid, key)
        if not p:
            return self._json({'ok': False, 'error': '没有这条串联或 key 不对'}, 403)
        return self._json({'ok': True})

    def _hub_links_get(self):
        try:
            with open(HUBS_FILE, 'r', encoding='utf-8') as f:
                j = json.load(f)
        except Exception:
            j = {}
        hubs = j.get('hubs') if isinstance(j, dict) else j
        if not isinstance(hubs, list):
            hubs = []
        out = []
        for h in hubs[:200]:
            if isinstance(h, str):
                out.append({'url': h, 'name': ''})
            elif isinstance(h, dict) and h.get('url'):
                out.append({'url': str(h.get('url'))[:300], 'name': str(h.get('name') or '')[:40]})
        return self._json({'ok': True, 'hubs': out})

    def _hub_links_post(self):
        if not hub_admin_ok(self):
            return self._json({'ok': False, 'error': '只有中枢本机能改目录'}, 403)
        body = self._body() or {}
        arr = body.get('hubs')
        if not isinstance(arr, list):
            return self._json({'ok': False, 'error': '格式不对'}, 400)
        out = []
        for h in arr[:200]:
            if isinstance(h, str):
                url, name = h, ''
            elif isinstance(h, dict):
                url, name = str(h.get('url') or ''), str(h.get('name') or '')
            else:
                continue
            url = url.strip()
            if not (url.startswith('http://') or url.startswith('https://')):
                continue
            if len(url) > 300:
                continue
            out.append({'url': url[:300], 'name': name[:40]})
        try:
            tmp = HUBS_FILE + '.tmp'
            with open(tmp, 'w', encoding='utf-8') as f:
                json.dump({'name': '互联中枢目录', 'hubs': out}, f, ensure_ascii=False, indent=2)
            os.replace(tmp, HUBS_FILE)
        except Exception as e:
            return self._json({'ok': False, 'error': '写不进去：' + str(e)}, 500)
        return self._json({'ok': True, 'hubs': out})

    def _lan_rooms(self):
        if not hub_open():
            return self._json({'ok': True, 'rooms': []})
        with OPEN_LOCK:
            _open_gc_locked()
            out = [{'code': r['code'], 'name': r['name'], 'cap': r['cap'], 'kind': r.get('kind') or 'p2p'}
                   for r in OPEN_ROOMS.values() if not r.get('private')]
        return self._json({'ok': True, 'rooms': out})

    def _lan_invite(self):
        if not hub_open():
            return self._json({'ok': False, 'error': '中枢已关闭'}, 503)
        if not hub_door_open():
            return self._json({'ok': False, 'door': True, 'error': hub_door_error()}, 403)
        q = self._query()
        code = str(q.get('code') or '').strip()
        with OPEN_LOCK:
            _open_gc_locked()
            r = OPEN_ROOMS.get(code)
            if not r:
                return self._json({'ok': False, 'error': '社团还没开或者关了'}, 404)
            return self._json({'ok': True, 'name': r['name'], 'invite': r['invite'], 'cap': r['cap'], 'kind': r.get('kind') or 'p2p'})

    def _lan_answer_post(self):
        if not hub_open():
            return self._json({'ok': False, 'error': '中枢已关闭'}, 503)
        body = self._body() or {}
        code = str(body.get('code') or '').strip()
        ans = str(body.get('answer') or '')
        if not ans or len(ans) > 20000:
            return self._json({'ok': False, 'error': '加入码不对'}, 400)
        with OPEN_LOCK:
            r = OPEN_ROOMS.get(code)
            if not r:
                return self._json({'ok': False, 'error': '社团不存在'}, 404)
            if len(r['answers']) > 20:
                r['answers'].pop(0)
            r['answers'].append({'answer': ans, 'ts': time.time()})
        return self._json({'ok': True})

    def _lan_answer_pull(self):
        if not hub_open():
            return self._json({'ok': False, 'error': '中枢已关闭'}, 503)
        q = self._query()
        code = str(q.get('code') or '').strip()
        token = str(q.get('token') or '').strip()
        with OPEN_LOCK:
            r = OPEN_ROOMS.get(code)
            if not r or token != r['token']:
                return self._json({'ok': False, 'error': '没有这个开放社团'}, 403)
            items = r['answers']
            r['answers'] = []
            r['ts'] = time.time()
        return self._json({'ok': True, 'answers': [x['answer'] for x in items]})

    # ---------- 好友：中枢只记关系 + 转交申请 ----------
    def _lan_close(self):
        body = self._body() or {}
        code = str(body.get('code') or '').strip()
        token = str(body.get('token') or '').strip()
        with OPEN_LOCK:
            r = OPEN_ROOMS.get(code)
            if not r:
                return self._json({'ok': True})
            if token and token != r['token']:
                return self._json({'ok': False, 'error': '只有团长能关门'}, 403)
            OPEN_ROOMS.pop(code, None)
        rooms_save()
        return self._json({'ok': True})

    def _friend_req(self):
        body = self._body() or {}
        frm = str(body.get('from') or '').strip()
        to = str(body.get('to') or '').strip()
        fn = str(body.get('fromName') or '')[:24]
        if not frm or not to or frm == to:
            return self._json({'ok': False, 'error': '参数不对'}, 400)
        with FRIEND_LOCK:
            queue = FRIEND_REQ.setdefault(to, [])
            if not any(x['from'] == frm for x in queue):
                queue.append({'from': frm, 'fromName': fn, 'ts': time.time()})
            if len(queue) > 50:
                del queue[0:len(queue) - 50]
        return self._json({'ok': True})

    def _friend_poll(self):
        q = self._query()
        me = str(q.get('id') or '').strip()
        with FRIEND_LOCK:
            items = FRIEND_REQ.pop(me, [])
        return self._json({'ok': True, 'requests': items})

    def _friend_ok(self):
        body = self._body() or {}
        a = str(body.get('a') or '').strip()
        b = str(body.get('b') or '').strip()
        an = str(body.get('aName') or '')[:24]
        bn = str(body.get('bName') or '')[:24]
        if not a or not b:
            return self._json({'ok': False, 'error': '参数不对'}, 400)
        with FRIEND_LOCK:
            FRIENDS.setdefault(a, {})[b] = bn or b
            FRIENDS.setdefault(b, {})[a] = an or a
            q = FRIEND_REQ.get(b)
            if q:
                FRIEND_REQ[b] = [x for x in q if x['from'] != a]
        return self._json({'ok': True})

    def _friend_list(self):
        q = self._query()
        me = str(q.get('id') or '').strip()
        with FRIEND_LOCK:
            items = [{'id': k, 'name': v} for k, v in (FRIENDS.get(me) or {}).items()]
        return self._json({'ok': True, 'friends': items})

    def _room_ws(self):
        if not hub_open():
            return self._json({'ok': False, 'error': '中枢已关闭'}, 503)
        if not hub_door_open():
            return self._json({'ok': False, 'door': True, 'error': hub_door_error()}, 403)
        cfg = hub_cfg()
        if cfg['maxMembers']:
            with ROOM_LOCK:
                total = sum(len(r['members']) for r in ROOMS.values())
            if total >= cfg['maxMembers']:
                return self._json({'ok': False, 'error': '中枢人数到上限（%d）' % cfg['maxMembers']}, 503)
        upgrade = self.headers.get('Upgrade') or ''
        key = (self.headers.get('Sec-WebSocket-Key') or '').strip()
        if 'websocket' not in upgrade.lower() or not key:
            return self._json({'ok': False, 'error': '需要 WebSocket 升级'}, 400)
        q = self._query()
        from urllib.parse import unquote
        code = unquote(str(q.get('code') or '')).strip()
        mid = unquote(str(q.get('id') or '')).strip()[:64] or ('m%d' % int(time.time() * 1000))
        name = unquote(str(q.get('name') or '')).strip()[:32] or '成员'
        token = unquote(str(q.get('token') or '')).strip()
        with HUB_CONN_LOCK:
            if mid in HUB_BANS:
                return self._json({'ok': False, 'banned': True, 'error': '你已被这台北枢封禁'}, 403)
        with ROOM_LOCK:
            r = ROOMS.get(code)
            if not r:
                return self._json({'ok': False, 'error': '社团不存在或已过期'}, 404)
            if len(r['members']) >= r['cap']:
                return self._json({'ok': False, 'error': '社团满了'}, 409)
            is_admin = bool(r['token']) and token == r['token']
            ban_until = (r.get('banned') or {}).get(mid)
            if ban_until is not None and (ban_until == 0 or time.time() < ban_until) and not is_admin:
                return self._json({'ok': False, 'error': '你被团长移出了这个社团'}, 403)

        self.send_response(101, 'Switching Protocols')
        self.send_header('Upgrade', 'websocket')
        self.send_header('Connection', 'Upgrade')
        self.send_header('Sec-WebSocket-Accept', _ws_accept_key(key))
        self.end_headers()
        self.close_connection = True
        try:
            self.connection.settimeout(ROOM_SOCK_TIMEOUT)
        except Exception:
            pass

        conn = RoomConn(self, code, mid, name)
        conn.admin = is_admin
        with ROOM_LOCK:
            r = ROOMS.get(code)
            if not r or len(r['members']) >= r['cap']:
                try:
                    conn.send_json({'t': 'room-full', 'why': '社团满了'})
                except Exception:
                    pass
                return
            r['members'][conn.id] = conn
            r['at'] = time.time()
        hub_conn_touch(conn.id, name, True)
        room_push_roster(code)
        try:
            with ROOM_LOCK:
                r = ROOMS.get(code)
                hist = list(r.get('history') or []) if r else []
            if hist:
                conn.send_json({'t': 'room-history', 'items': hist})
        except Exception:
            pass
        try:
            while conn.alive:
                op, data = _ws_recv(self.rfile)
                if op == 0x8:
                    break
                if op == 0x9:
                    conn.send(0xA, data)
                    continue
                if op not in (0x1, 0x2):
                    continue
                conn.rx = time.time()
                if op == 0x2 and not conn.admin:
                    # 配额 / 限速只作用于群员（团长不受自己设的限）
                    with ROOM_LOCK:
                        r2 = ROOMS.get(code)
                        quota = int(r2.get('quota') or 0) if r2 else 0
                        rate = int(r2.get('rate') or 0) if r2 else 0
                    if quota and conn.bytes + len(data) > quota:
                        if not conn.quota_warned:
                            conn.quota_warned = True
                            try:
                                conn.send_json({'t': 'quota-hit', 'limit': quota, 'used': conn.bytes})
                            except Exception:
                                pass
                        continue
                    if conn.member_rate:
                        rate = conn.member_rate
                    if rate > 0:
                        now2 = time.time()
                        if not conn.tok_ready:
                            conn.tok_ready = True
                            conn.tokens = float(rate)
                            conn.tok_ts = now2
                        conn.tokens = min(float(rate), conn.tokens + (now2 - conn.tok_ts) * rate)
                        conn.tok_ts = now2
                        need = len(data)
                        if conn.tokens < need:
                            # 睡够令牌：靠 TCP 背压让发送方自然慢下来
                            time.sleep(min(2.0, (need - conn.tokens) / float(rate)))
                            if not conn.alive:
                                break
                            now2 = time.time()
                            conn.tokens = min(float(rate), conn.tokens + (now2 - conn.tok_ts) * rate)
                            conn.tok_ts = now2
                        conn.tokens = max(0.0, conn.tokens - need)
                if op == 0x2:
                    conn.bytes += len(data)          # 二进制 = 文件块，记账
                if op == 0x1:
                    try:
                        m = json.loads(data.decode('utf-8'))
                    except Exception:
                        m = None
                    if isinstance(m, dict):
                        t = m.get('t')
                        if t == 'ws-ping':
                            conn.send_json({'t': 'ws-pong', 'ts': m.get('ts')})
                            continue
                        if t == 'room-admin':
                            if conn.admin:
                                room_admin(code, conn, m)
                            continue
                        if t == 'presence':
                            conn.in_room = bool(m.get('inRoom'))
                            room_push_roster(code)
                            continue
                        if t == 'msg' and not conn.muted:
                            # 给后来进来的人留一份最近的消息（进入社团才收到）
                            with ROOM_LOCK:
                                r3 = ROOMS.get(code)
                                if r3 is not None:
                                    hist = r3.setdefault('history', [])
                                    hist.append({
                                        'id': m.get('id') or '',
                                        'body': str(m.get('body') or '')[:2000],
                                        'ts': m.get('ts') or 0,
                                        'from': m.get('from') or {},
                                    })
                                    if len(hist) > 50:
                                        del hist[0:len(hist) - 50]
                            room_broadcast(code, conn, op, data)
                            continue
                        if t == 'file-begin' and not conn.admin:
                            with ROOM_LOCK:
                                r2 = ROOMS.get(code)
                                fmax = int(r2.get('file_max') or 0) if r2 else 0
                            if fmax and int(m.get('size') or 0) > fmax:
                                try:
                                    conn.send_json({'t': 'file-deny', 'sid': m.get('sid'),
                                                    'why': '单个文件超过社团上限（' + _mb_text(fmax) + '）'})
                                except Exception:
                                    pass
                                continue
                        if conn.muted and t in ('msg', 'sticker'):
                            conn.send_json({'t': 'muted-block'})
                            continue
                room_broadcast(code, conn, op, data)
        except Exception:
            pass
        finally:
            with ROOM_LOCK:
                r = ROOMS.get(code)
                if r:
                    if r['members'].get(conn.id) is conn:
                        r['members'].pop(conn.id, None)
                    r['at'] = time.time()
            hub_conn_touch(conn.id, conn.name, False)
            conn.alive = False
            try:
                conn.send(0x8, b'')
            except Exception:
                pass
            room_push_roster(code)

    def _hub_relay_find(self):
        """串联中枢来问：你这边有没有人在等这个 pair？"""
        body = self._body() or {}
        pair = str(body.get('pair') or '')[:96]
        fromid = str(body.get('from') or '')[:64]
        try:
            ts = int(body.get('ts') or 0)
        except Exception:
            ts = 0
        sig = str(body.get('sig') or '')[:64]
        peer = hub_peer_by_id(fromid)
        if not peer or not pair:
            return self._json({'ok': False, 'error': '不是已串联的中枢'}, 403)
        if abs(time.time() - ts) > 120:
            return self._json({'ok': False, 'error': '时间戳过期'}, 403)
        if hub_relay_sig(peer.get('key'), hub_relay_canon('relay-find', pair, fromid, ts)) != sig:
            return self._json({'ok': False, 'error': '签名不对'}, 403)
        w = relay_waiting(pair)
        if w is None:
            return self._json({'ok': True, 'waiting': 0})
        return self._json({'ok': True, 'waiting': 1, 'mid': w.id, 'name': w.name})

    def _hub_relay_attach(self):
        """另一个中枢连过来当「虚拟对端」：把它的客户端和本机等的那个客户端桥起来。"""
        from urllib.parse import unquote
        q = self._query()
        pair = unquote(str(q.get('pair') or '')).strip()[:96]
        fromid = unquote(str(q.get('from') or '')).strip()[:64]
        cmid = unquote(str(q.get('cmid') or '')).strip()[:64]
        cname = unquote(str(q.get('cname') or '')).strip()[:32]
        try:
            ts = int(q.get('ts') or 0)
        except Exception:
            ts = 0
        sig = str(q.get('sig') or '')[:64]
        peer = hub_peer_by_id(fromid)
        if not peer or not pair or not cmid:
            return self._json({'ok': False, 'error': '参数不全或不是已串联中枢'}, 403)
        if abs(time.time() - ts) > 120:
            return self._json({'ok': False, 'error': '时间戳过期'}, 403)
        if hub_relay_sig(peer.get('key'), hub_relay_canon('relay-attach', pair, fromid, ts, cmid)) != sig:
            return self._json({'ok': False, 'error': '签名不对'}, 403)
        local = relay_waiting(pair)
        if local is None:
            return self._json({'ok': False, 'error': '本机没有在等这个 pair 的客户端'}, 409)
        upgrade = self.headers.get('Upgrade') or ''
        key = (self.headers.get('Sec-WebSocket-Key') or '').strip()
        if 'websocket' not in upgrade.lower() or not key:
            return self._json({'ok': False, 'error': '需要 WebSocket 升级'}, 400)
        self.send_response(101, 'Switching Protocols')
        self.send_header('Upgrade', 'websocket')
        self.send_header('Connection', 'Upgrade')
        self.send_header('Sec-WebSocket-Accept', _ws_accept_key(key))
        self.end_headers()
        self.close_connection = True
        try:
            self.connection.settimeout(ROOM_SOCK_TIMEOUT)
        except Exception:
            pass
        conn = RoomConn(self, pair, cmid, cname)
        conn.virtual = True
        with RELAY_LOCK:
            RELAY.setdefault(pair, {})[cmid] = conn
        _relay_log('fed attach pair=%s from=%s cmid=%s' % (pair, fromid, cmid))
        try:
            local.send_json({'t': 'relay-peer', 'id': cmid, 'name': cname})
        except Exception:
            pass
        try:
            while conn.alive:
                op, data = _ws_recv(self.rfile)
                if op == 0x8:
                    break
                if op == 0x9:
                    conn.send(0xA, data)
                    continue
                if op in (0x1, 0x2):
                    target = relay_other(pair, cmid)
                    if target is not None:
                        try:
                            target.send(op, data)
                        except Exception:
                            pass
        except Exception:
            pass
        finally:
            with RELAY_LOCK:
                slot = RELAY.get(pair) or {}
                if slot.get(cmid) is conn:
                    slot.pop(cmid, None)
            conn.alive = False
            try:
                conn.send(0x8, b'')
            except Exception:
                pass
            try:
                local.send_json({'t': 'relay-peer-off', 'id': cmid})
            except Exception:
                pass

    def _relay_ws(self):
        """一对一的兜底中继：两端各自的 id 不同，pair 相同，就把帧转给另一端。"""
        if not hub_open():
            return self._json({'ok': False, 'error': '中枢已关闭'}, 503)
        if not hub_door_open():
            return self._json({'ok': False, 'door': True, 'error': hub_door_error()}, 403)
        upgrade = self.headers.get('Upgrade') or ''
        key = (self.headers.get('Sec-WebSocket-Key') or '').strip()
        if 'websocket' not in upgrade.lower() or not key:
            return self._json({'ok': False, 'error': '需要 WebSocket 升级'}, 400)
        from urllib.parse import unquote
        q = self._query()
        pair = unquote(str(q.get('pair') or '')).strip()[:96]
        # 备用频道键：两边算法不一致时（一边指纹版、一边 id 版）靠它配对
        alt = unquote(str(q.get('alt') or '')).strip()[:96]
        mid = unquote(str(q.get('id') or '')).strip()[:64]
        name = unquote(str(q.get('name') or '')).strip()[:32]
        if not pair or not mid:
            return self._json({'ok': False, 'error': '参数不全'}, 400)
        self.send_response(101, 'Switching Protocols')
        self.send_header('Upgrade', 'websocket')
        self.send_header('Connection', 'Upgrade')
        self.send_header('Sec-WebSocket-Accept', _ws_accept_key(key))
        self.end_headers()
        self.close_connection = True
        try:
            self.connection.settimeout(ROOM_SOCK_TIMEOUT)
        except Exception:
            pass
        conn = RoomConn(self, pair, mid, name)
        with RELAY_LOCK:
            slot = RELAY.setdefault(pair, {})
            old = slot.get(mid)
            slot[mid] = conn
            if alt and alt != pair:
                slot2 = RELAY.setdefault(alt, {})
                if slot2.get(mid) is None:
                    slot2[mid] = conn
        def _relay_peer():
            x = relay_other(pair, mid)
            if x is None and alt:
                x = relay_other(alt, mid)
            return x
        _relay_log('join pair=%s alt=%s id=%s name=%s peer=%s' % (pair, alt, mid, name, 'yes' if _relay_peer() is not None else 'no'))
        if old is not None:
            try:
                old.close_socket('你在别处重新连了')
            except Exception:
                pass
        peer = _relay_peer()
        if peer is not None:
            try:
                peer.send_json({'t': 'relay-peer', 'id': mid, 'name': name})
            except Exception:
                pass
            # 关键：后进来的一端也要知道自己配上了。只通知老的一端的话，
            # 新来的那条连接永远收不到 relay-peer，客户端就不会收敛掉多余的中继候选。
            try:
                conn.send_json({'t': 'relay-peer', 'id': peer.id, 'name': peer.name})
            except Exception:
                pass
        else:
            # 本机没人等这个 pair：试试串联中枢那边（中枢间转发）
            try:
                _fed_try(pair, conn)
            except Exception:
                pass
        try:
            while conn.alive:
                op, data = _ws_recv(self.rfile)
                if op == 0x8:
                    break
                if op == 0x9:
                    conn.send(0xA, data)
                    continue
                if op not in (0x1, 0x2):
                    continue
                conn.rx = time.time()
                peer = _relay_peer()
                if peer is not None:
                    try:
                        peer.send(op, data)
                    except Exception:
                        pass
        except Exception:
            pass
        finally:
            with RELAY_LOCK:
                slot = RELAY.get(pair)
                if slot is not None and slot.get(mid) is conn:
                    slot.pop(mid, None)
                    if not slot:
                        RELAY.pop(pair, None)
                if alt and alt != pair:
                    slot2 = RELAY.get(alt)
                    if slot2 is not None and slot2.get(mid) is conn:
                        slot2.pop(mid, None)
                        if not slot2:
                            RELAY.pop(alt, None)
            conn.alive = False
            try:
                conn.send(0x8, b'')
            except Exception:
                pass
            peer = _relay_peer()
            if peer is not None:
                try:
                    peer.send_json({'t': 'relay-peer-off', 'id': mid})
                except Exception:
                    pass

    # ---------- OPTIONS ----------
    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()

    # ---------- GET ----------
    # ---------- 内置浏览器的反向代理：去掉禁止内嵌的响应头 ----------
    def _import_bookmarks(self):
        import glob
        home = os.path.expanduser('~')
        cands = []
        for base in ('Google/Chrome', 'Microsoft/Edge', 'Chromium', 'BraveSoftware/Brave-Browser'):
            for prof in ('Default', 'Profile 1', 'Profile 2', 'Profile 3'):
                cands.append(os.path.join(os.environ.get('LOCALAPPDATA', ''), base, 'User Data', prof, 'Bookmarks'))
                cands.append(os.path.join(home, '.config', base, prof, 'Bookmarks'))
        items, sources = [], []
        seen = set()

        def walk(node):
            if not isinstance(node, dict):
                return
            if node.get('type') == 'url':
                u = (node.get('url') or '').strip()
                if u.startswith(('http://', 'https://')) and u not in seen:
                    seen.add(u)
                    items.append({'title': (node.get('name') or u)[:80], 'url': u})
            for ch in (node.get('children') or []):
                walk(ch)

        for f in cands:
            if not os.path.isfile(f):
                continue
            try:
                with open(f, 'r', encoding='utf-8') as fh:
                    data = json.load(fh)
                before = len(items)
                for root in (data.get('roots') or {}).values():
                    walk(root)
                if len(items) > before:
                    sources.append(os.path.basename(os.path.dirname(os.path.dirname(f))) + '/' + os.path.basename(os.path.dirname(f)))
            except Exception:
                continue
        return self._json({'ok': True, 'count': len(items), 'items': items[:400], 'sources': sorted(set(sources))})

    def _board_list(self):
        if not hub_open():
            return self._json({'ok': False, 'error': '中枢已关闭'}, 503)
        rows = board_index()
        out = []
        for r in rows:
            out.append({k: r.get(k) for k in ('id', 'kind', 'text', 'name', 'size', 'mime', 'ts', 'by')})
        return self._json({'ok': True, 'posts': out,
                           'limits': {'maxFile': BOARD_MAX_FILE, 'maxText': BOARD_MAX_TEXT, 'maxPosts': BOARD_MAX_POSTS}})

    def _board_post(self):
        if not hub_open():
            return self._json({'ok': False, 'error': '中枢已关闭'}, 503)
        if not hub_admin_ok(self):
            return self._json({'ok': False, 'error': '只有团长（中枢本机）能发公告'}, 403)
        body = self._body() or {}
        text = str(body.get('text') or '').strip()[:BOARD_MAX_TEXT]
        name = str(body.get('name') or '').strip()[:80]
        mime = str(body.get('mime') or '').strip()[:80]
        by = str(body.get('by') or '').strip()[:32]
        data = body.get('data') or ''
        pid = hashlib.sha1(os.urandom(16)).hexdigest()[:12]
        kind = 'text'
        size = 0
        if data:
            import base64 as _b64
            try:
                raw = _b64.b64decode(str(data)[:BOARD_MAX_FILE * 2])
            except Exception:
                return self._json({'ok': False, 'error': '附件数据不对'}, 400)
            if len(raw) > BOARD_MAX_FILE:
                return self._json({'ok': False, 'error': '附件太大（上限 %s）' % _mb_text(BOARD_MAX_FILE)}, 413)
            _board_dir()
            try:
                with open(os.path.join(BOARD_DIR, pid + '.bin'), 'wb') as f:
                    f.write(raw)
            except Exception as e:
                return self._json({'ok': False, 'error': '存不下：' + str(e)}, 500)
            size = len(raw)
            kind = 'img' if (mime or '').startswith('image/') else 'file'
        if not text and kind == 'text':
            return self._json({'ok': False, 'error': '写点内容再发'}, 400)
        post = {'id': pid, 'kind': kind, 'text': text, 'name': name,
                'size': size, 'mime': mime, 'ts': time.time(), 'by': by}
        board_add(post)
        return self._json({'ok': True, 'post': post})

    def _board_file(self):
        if not hub_open():
            return self._json({'ok': False, 'error': '中枢已关闭'}, 503)
        from urllib.parse import parse_qs, urlparse
        qs = parse_qs(urlparse(self.path).query)
        pid = (qs.get('id') or [''])[0][:32]
        if not re.match(r'^[0-9a-f]{6,32}$', pid or ''):
            return self._json({'ok': False, 'error': 'bad id'}, 400)
        row = next((r for r in board_index() if r.get('id') == pid), None)
        if not row:
            return self._json({'ok': False, 'error': '公告不存在'}, 404)
        try:
            with open(os.path.join(BOARD_DIR, pid + '.bin'), 'rb') as f:
                raw = f.read()
        except Exception:
            return self._json({'ok': False, 'error': '附件没了'}, 404)
        self.send_response(200)
        self.send_header('Content-Type', row.get('mime') or 'application/octet-stream')
        self.send_header('Content-Length', str(len(raw)))
        self.send_header('Cache-Control', 'no-store')
        nm = str(row.get('name') or ('附件-' + pid)).replace('"', '')
        try:
            from urllib.parse import quote
            self.send_header('Content-Disposition', "attachment; filename*=UTF-8''" + quote(nm))
        except Exception:
            pass
        self._cors()
        self.end_headers()
        try:
            self.wfile.write(raw)
        except Exception:
            pass

    def _board_del(self):
        if not hub_admin_ok(self):
            return self._json({'ok': False, 'error': '只有团长能删'}, 403)
        body = self._body() or {}
        pid = str(body.get('id') or '')[:32]
        with BOARD_LOCK:
            rows = board_index()
            board_save_index([r for r in rows if r.get('id') != pid])
        try:
            os.remove(os.path.join(BOARD_DIR, pid + '.bin'))
        except Exception:
            pass
        return self._json({'ok': True})

    def _msg_list(self):
        if not hub_open():
            return self._json({'ok': False, 'error': '中枢已关闭'}, 503)
        return self._json({'ok': True, 'msgs': msg_list(), 'maxLen': MSG_MAX_LEN})

    def _msg_post(self):
        if not hub_open():
            return self._json({'ok': False, 'error': '中枢已关闭'}, 503)
        try:
            ip = str(self.client_address[0] or '')
        except Exception:
            ip = ''
        now = time.time()
        with MSG_LOCK:
            if now - (MSG_RATE.get(ip) or 0) < 2:
                return self._json({'ok': False, 'error': '发太快了，歇两秒'}, 429)
            MSG_RATE[ip] = now
            if len(MSG_RATE) > 500:
                for k in [k for k, v in MSG_RATE.items() if now - v > 600]:
                    MSG_RATE.pop(k, None)
        body = self._body() or {}
        text = str(body.get('text') or '').strip()[:MSG_MAX_LEN]
        if not text:
            return self._json({'ok': False, 'error': '写点内容'}, 400)
        m = {'id': hashlib.sha1(os.urandom(16)).hexdigest()[:12], 'text': text,
             'by': str(body.get('by') or '').strip()[:32],
             'uid': str(body.get('uid') or '').strip()[:64], 'ts': time.time()}
        msg_add(m)
        return self._json({'ok': True, 'msg': m})

    def _msg_offline_post(self):
        if not hub_open():
            return self._json({'ok': False, 'error': '中枢已关闭'}, 503)
        body = self._body() or {}
        n = hub_offline_post(body.get('to'), body.get('from'), body.get('env'), body.get('ttl'))
        if not n:
            return self._json({'ok': False, 'error': '参数不对或信封太大'}, 400)
        return self._json({'ok': True, 'stored': 1})

    def _msg_offline_poll(self):
        to = ''
        try:
            q = self.path.split('?', 1)[1] if '?' in self.path else ''
            for kv in q.split('&'):
                if kv.startswith('id='):
                    to = kv[3:]
        except Exception:
            to = ''
        rows = hub_offline_poll(to)
        items = [{'from': r.get('from'), 'env': r.get('env'), 'at': r.get('at')} for r in rows]
        return self._json({'ok': True, 'items': items, 'count': len(items)})

    def _msg_del(self):
        if not hub_admin_ok(self):
            return self._json({'ok': False, 'error': '只有团长能删'}, 403)
        body = self._body() or {}
        mid = str(body.get('id') or '')[:32]
        with MSG_LOCK:
            rows = [r for r in msg_list() if r.get('id') != mid]
            _hub_data_ensure()
            _hub_write_json(MSG_FILE, rows)
        return self._json({'ok': True})

    def _serve_size(self):
        from urllib.parse import parse_qs, urlparse
        qs = parse_qs(urlparse(self.path).query)
        target = (qs.get('u') or [''])[0]
        if not target.lower().startswith(('http://', 'https://')):
            return self._json({'ok': False, 'error': 'bad url'}, 400)
        try:
            req = urllib.request.Request(target, method='HEAD', headers={
                'User-Agent': _pick_ua(self),
                'Accept-Encoding': 'identity',
            })
            with urllib.request.urlopen(req, timeout=10, context=_SSL_CTX) as r:
                size = r.headers.get('Content-Length')
                ctype = r.headers.get('Content-Type', '')
            return self._json({'ok': True, 'size': int(size) if size and size.isdigit() else 0,
                               'type': ctype, 'url': target})
        except Exception as e:
            return self._json({'ok': False, 'error': str(e)}, 200)

    def _serve_proxy(self):
        from urllib.parse import parse_qs, urlparse
        _esc = lambda t: (t or '').replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
        qs = parse_qs(urlparse(self.path).query)
        target = (qs.get('u') or [''])[0]
        if not target.lower().startswith(('http://', 'https://')):
            return self._json({'ok': False, 'error': 'bad url'}, 400)
        try:
            _ua = (qs.get('ua') or [''])[0]
            if _ua == 'mobile':
                _ua = UA_MOBILE
            elif _ua == 'desktop':
                _ua = UA_DESKTOP
            else:
                _ua = _pick_ua(self)
            req = urllib.request.Request(target, headers={
                'User-Agent': _ua,
                'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
                'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.6',
                'Accept-Encoding': 'gzip, deflate',
            })
            with urllib.request.urlopen(req, timeout=18, context=_SSL_CTX) as r:
                raw = r.read(6 * 1024 * 1024)
                ctype = r.headers.get('Content-Type', 'text/html')
                final = r.geturl()
                enc = (r.headers.get('Content-Encoding') or '').lower()
            if 'gzip' in enc:
                import gzip as _gz
                try:
                    raw = _gz.decompress(raw)
                except Exception:
                    pass
            elif 'deflate' in enc:
                import zlib as _zl
                try:
                    raw = _zl.decompress(raw)
                except Exception:
                    try:
                        raw = _zl.decompress(raw, -15)
                    except Exception:
                        pass
            elif 'br' in enc:
                try:
                    import brotli as _br
                    raw = _br.decompress(raw)
                except Exception:
                    pass
        except Exception as e:
            msg = str(e)
            html = ('<!doctype html><meta charset="utf-8"><body style="font:13px sans-serif;padding:16px">'
                    '<b>打不开这个网址</b><p>' + _esc(target) + '</p><p style="color:#a00">'
                    + _esc(msg) + '</p></body>').encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Content-Length', str(len(html)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            try:
                self.wfile.write(html)
            except Exception:
                pass
            return
        want_dl = (qs.get('dl') or [''])[0] == '1'
        if 'text/html' in ctype.lower():
            text = raw.decode('utf-8', 'replace')
            text = _proxy_inject(text, final)
            raw = text.encode('utf-8')
            ctype = 'text/html; charset=utf-8'
        self.send_response(200)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(raw)))
        self.send_header('Cache-Control', 'no-store')
        if want_dl:
            from urllib.parse import unquote
            name = unquote(target.split('?')[0].rstrip('/').split('/')[-1]) or 'download'
            self.send_header('Content-Disposition', 'attachment; filename="%s"' % name.replace('"', ''))
        self.end_headers()
        try:
            self.wfile.write(raw)
        except Exception:
            pass

    def do_GET(self):
        path = self.path.split('?')[0]

        # 中枢控制台
        if path == '/__hub/state':
            return self._hub_state()
        if path == '/__hub/link/info':
            return self._hub_link_info_get()
        if path == '/__hub/link/pending':
            return self._hub_link_pending()
        if path == '/__hub/link/out':
            return self._hub_link_out_list()
        if path == '/__hub/link/list':
            return self._hub_link_list()
        if path == '/__hub/links':
            return self._hub_links_get()
        if path == '/__hub/conns':
            return self._hub_conns_get()

        # 社团中枢（WebSocket 中转）
        if path == '/__room/ws':
            return self._room_ws()
        if path == '/__relay/ws':
            return self._relay_ws()
        if path == '/__hub/relay/attach':
            return self._hub_relay_attach()
        if path == '/__room/info':
            return self._room_info()
        if path == '/__room/list':
            return self._room_list()
        if path == '/__lan/rooms':
            return self._lan_rooms()
        if path == '/__friend/poll':
            return self._friend_poll()
        if path == '/__friend/list':
            return self._friend_list()
        if path == '/__room/invite':
            return self._lan_invite()
        if path == '/__room/answer':
            return self._lan_answer_pull()

        if path == '/__board/list':
            return self._board_list()
        if path == '/__board/file':
            return self._board_file()
        if path == '/__msg/list':
            return self._msg_list()

        if path == '/__proxy':
            return self._serve_proxy()

        if path == '/__size':
            return self._serve_size()

        if path == '/__favs/import':
            return self._import_bookmarks()

        if path == '/__hubs':
            return self._hubs()

        if path == '/__hub/usage':
            return self._json(_usage_snapshot())

        if path == '/__hub/public/state':
            return self._hub_public('state')

        if path == '/__info':
            return self._json({
                'lan': hub_public_url() + '/',
                'port': PORT,
                'signal': True,
                'forum': bool(_bbs),
                'open': hub_open(),
                'door': hub_door_open(),
                'name': (hub_cfg().get('name') or ''),
                'cfg': hub_cfg(),
                **hub_identity(),
            })

        # 论坛 / 资源库 / 联机社团
        if _bbs and path.startswith('/bbs') and _bbs.handle(self):
            return

        # 附近的站点目录
        if path == '/__lan/sites':
            try:
                sites = scan_sites()
            except Exception as e:
                return self._json({'ok': False, 'error': str(e)}, 500)
            return self._json({'ok': True, 'sites': sites, 'count': len(sites)})

        # 听书：语音能力查询 + 音频文件
        if path == '/__tts/info':
            if not tts_guest_ok(self):
                return self._json({'ok': False, 'error': '需要口令'}, 403)
            return self._json({
                'ok': True,
                'edge': bool(_edge),
                'local': bool(tts_local_cfg()),
                'voice': TTS_VOICE,
                'rate': TTS_RATE,
            })
        if path.startswith('/__tts/') and os.path.splitext(path)[1].lower() in AUDIO_EXT:
            fn = os.path.basename(path)
            fp = os.path.join(TTS_DIR, fn)
            if not (os.path.isfile(fp) and os.path.getsize(fp) > 0):
                return self._json({'error': 'not found'}, 404)
            try:
                with open(fp, 'rb') as f:
                    data = f.read()
            except Exception as e:
                return self._json({'error': str(e)}, 500)
            self.send_response(200)
            self.send_header('Content-Type', AUDIO_EXT.get(os.path.splitext(fp)[1].lower(), 'audio/mpeg'))
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Access-Control-Allow-Origin', '*')
            self.send_header('Cache-Control', 'public, max-age=604800')
            self.end_headers()
            try:
                self.wfile.write(data)
            except Exception:
                pass
            return

        if path == '/__msg/offline/poll':
            return self._msg_offline_poll()

        if path == '/__lan/peers':
            q = self._query()
            me = q.get('id', '')
            my_ip = self.client_address[0] if self.client_address else ''
            my_net = '.'.join(my_ip.split('.')[:3]) if my_ip.count('.') == 3 else ''
            now2 = time.time()
            with SIGNAL_LOCK:
                for k in list(LAN_PEERS):
                    if now2 - LAN_PEERS[k]['at'] > LAN_TTL:
                        LAN_PEERS.pop(k, None)
                peers = []
                for k, v in LAN_PEERS.items():
                    if k == me:
                        continue
                    ip = str(v.get('ip') or '')
                    net = '.'.join(ip.split('.')[:3]) if ip.count('.') == 3 else ''
                    def _loop(x):
                        return (not x) or x in ('127.0.0.1', '::1') or x.startswith('127.') or x.startswith('::ffff:127.')
                    # 回环（本机页面）和局域网视为同一网络；其余按 /24 过滤
                    if not (_loop(ip) or _loop(my_ip)):
                        if my_net and net and net != my_net:
                            continue
                    # 附近设备列表只需要 id/name/ip；头像动辄几十 KB，别每 12 秒传一遍
                    pv = dict(v)
                    pv.pop('avatar', None)
                    peers.append(pv)
            return self._json({'peers': peers, 'self': me, 'net': my_net})
        if path == '/__lan/presence':
            q = self._query()
            ids = [x for x in str(q.get('ids') or '').split(',') if x]
            nowp = time.time()
            out = {}
            with SIGNAL_LOCK:
                for i in ids:
                    v = LAN_PEERS.get(i)
                    out[i] = bool(v and nowp - v['at'] <= LAN_TTL)
            return self._json({'ok': True, 'online': out})

        if path == '/__lan/sigpoll':
            q = self._query()
            who = q.get('id', '')
            now4 = time.time()
            with SIGNAL_LOCK:
                items = LAN_SIG.pop(who, []) if who else []
                items = [x for x in items if now4 - x['at'] < 120]
            return self._json({'ok': True, 'items': items})

        if path == '/__lan/inbox':
            q = self._query()
            who = q.get('id', '')
            now3 = time.time()
            with SIGNAL_LOCK:
                items = LAN_HI.pop(who, []) if who else []
                items = [x for x in items if now3 - x['at'] < 600]
            return self._json({'ok': True, 'items': items})

        if path == '/__signal/get':
            q = self._query()
            code = q.get('code', '')
            kind = q.get('kind', 'offer')
            try:
                timeout = min(60.0, max(1.0, float(q.get('timeout', '25'))))
            except ValueError:
                timeout = 25.0
            deadline = time.time() + timeout
            while time.time() < deadline:
                with SIGNAL_LOCK:
                    room = SIGNAL.get(code)
                    if room and room.get(kind):
                        val = room[kind]
                        room['at'] = time.time()
                        return self._json({'sdp': val})
                time.sleep(0.25)
            return self._json({'error': 'timeout'}, 408)

        if path == '/__blind/get':
            if not _blind_allow(self.client_address[0], BLIND_GET_PER_MIN, time.time()):
                return self._json({'error': 'rate limited'}, 429)
            q = self._query()
            room = q.get('room', '')
            role = q.get('role', 'offer')
            if role not in ('offer', 'answer') or not re.fullmatch(r'[0-9a-fA-F]{16,64}', room):
                return self._json({'error': 'bad request'}, 400)
            try:
                wait = min(30.0, max(0.0, float(q.get('wait', '0'))))
            except ValueError:
                wait = 0.0
            deadline = time.time() + wait
            while True:
                now = time.time()
                with BLIND_LOCK:
                    _blind_sweep(now)
                    rec = BLIND.get(room)
                    items = [{'payload': x['payload'], 'nonce': x['nonce'], 'at': x['at']}
                             for x in (rec or {}).get(role, [])]
                if items or time.time() >= deadline:
                    return self._json({'ok': True, 'room': room, 'role': role, 'items': items})
                time.sleep(0.25)

        if path == '/__voicepacks':
            # 语音包默认只给电脑本机看；中枢开门时允许其它设备读取（手机借电脑算力合成）。
            # 增删仍然只允许本机（下面的 POST 分支有回环校验）。
            if not self._loopback() and not hub_open():
                return self._json({'ok': True, 'items': [], 'shared': False})
            items = []
            for it in packs_load():
                items.append({'id': it.get('id'), 'name': it.get('name'),
                              'lang': it.get('lang'), 'at': it.get('at')})
            return self._json({'ok': True, 'items': items, 'shared': True})

        # 静态文件白名单：非本机访问只放行 App 需要的文件，别把整个项目目录暴露出去
        return self._static_guard()

    # 对外暴露时允许的静态文件（相对 ROOT）
    STATIC_OK = {
        'index.html', 'favicon.ico', 'manifest.json', 'manifest.webmanifest',
        'icon-32.png', 'icon-180.png', 'idle.png',
    }

    def _static_guard(self):
        try:
            if self._loopback():
                return super().do_GET()
        except Exception:
            pass
        rel = (self.path.split('?')[0] or '/').lstrip('/') or 'index.html'
        if rel in self.STATIC_OK:
            return super().do_GET()
        # 允许常见静态资源子目录（将来放图片/字体用）；禁止 .. 穿越
        if '..' not in rel and re.match(r'^(assets|static|img|icons|css|js|fonts|media)/[A-Za-z0-9._\-]+$', rel):
            return super().do_GET()
        try:
            self._json({'ok': False, 'error': 'not found'}, 404)
        except Exception:
            pass

    # ---------- POST ----------
    def do_POST(self):
        path = self.path.split('?')[0]
        # 公网防刷：只对资源重的写端点限流，回环/反代后的真实 IP 计
        try:
            if not _post_rate_ok(self, path, time.time()):
                return self._json({'ok': False, 'error': '操作太频繁，稍后再试'}, 429)
        except Exception:
            pass

        # 中枢控制台
        if path == '/__hub/state':
            return self._hub_set()
        if path == '/__hub/config':
            return self._hub_config_post()
        if path == '/__hub/links':
            return self._hub_links_post()
        if path == '/__hub/door':
            return self._hub_door()
        if path == '/__hub/ratelimit':
            return self._hub_ratelimit()
        if path == '/__hub/public/start':
            return self._hub_public('start')
        if path == '/__hub/public/stop':
            return self._hub_public('stop')
        if path == '/__hub/public/state':
            return self._hub_public('state')
        if path == '/__hub/conns':
            return self._hub_conns_post()
        if path == '/__hub/presence':
            return self._hub_presence()
        if path == '/__hub/relay/find':
            return self._hub_relay_find()
        if path == '/__hub/link/request':
            return self._hub_link_request()
        if path == '/__hub/link/approve':
            return self._hub_link_approve()
        if path == '/__hub/link/accept':
            return self._hub_link_accept()
        if path == '/__hub/link/reject':
            return self._hub_link_reject()
        if path == '/__hub/link/out':
            return self._hub_link_out()
        if path == '/__hub/link/remove':
            return self._hub_link_remove()
        if path == '/__hub/link/leave':
            return self._hub_link_leave()

        # 团长公告栏
        if path == '/__board/post':
            return self._board_post()
        if path == '/__board/del':
            return self._board_del()
        if path == '/__msg/post':
            return self._msg_post()
        if path == '/__msg/del':
            return self._msg_del()
        if path == '/__msg/offline':
            return self._msg_offline_post()
        if path == '/__msg/offline/poll':
            return self._msg_offline_poll()

        # 社团中枢：建房
        if path == '/__room/new':
            return self._room_new()
        if path == '/__room/delete':
            return self._room_delete()
        if path == '/__room/config':
            return self._room_config()
        if path == '/__room/transfer':
            return self._room_transfer()
        if path == '/__room/open':
            return self._lan_open()
        if path == '/__room/close':
            return self._lan_close()
        if path == '/__friend/req':
            return self._friend_req()
        if path == '/__friend/ok':
            return self._friend_ok()
        if path == '/__room/answer':
            return self._lan_answer_post()

        # 论坛发帖 / 上传 / 社团登记
        if _bbs and path.startswith('/bbs') and _bbs.handle(self):
            return

        # 导出 MP3：前端把整章 WAV 发过来，用本机 ffmpeg 转码
        if path == '/__mp3':
            if not tts_guest_ok(self):
                return self._json({'ok': False, 'error': '需要口令'}, 403)
            try:
                n = int(self.headers.get('Content-Length') or 0)
                raw = self.rfile.read(n)
            except Exception:
                raw = b''
            if len(raw) < 200:
                return self._json({'ok': False, 'error': 'empty'}, 400)
            ff = os.path.join(ROOT, 'tts-local', 'src', 'GPT-SoVITS-main', 'runtime', 'ffmpeg.exe')
            if not os.path.isfile(ff):
                return self._json({'ok': False, 'error': '没有 ffmpeg'}, 500)
            stamp = str(time.time_ns())
            fin = os.path.join(TTS_DIR, 'exp_' + stamp + '.wav')
            fout = os.path.join(TTS_DIR, 'exp_' + stamp + '.mp3')
            try:
                os.makedirs(TTS_DIR, exist_ok=True)
                with open(fin, 'wb') as f:
                    f.write(raw)
                rf = subprocess.run([ff, '-y', '-hide_banner', '-loglevel', 'error',
                                     '-i', fin, '-codec:a', 'libmp3lame', '-b:a', '128k', fout],
                                    capture_output=True, timeout=180)
                if rf.returncode != 0 or not os.path.isfile(fout):
                    return self._json({'ok': False, 'error': '转码失败'}, 500)
                data = open(fout, 'rb').read()
                self.send_response(200)
                self.send_header('Content-Type', 'audio/mpeg')
                self.send_header('Content-Length', str(len(data)))
                self.send_header('Cache-Control', 'no-store')
                self.end_headers()
                self.wfile.write(data)
            finally:
                for f in (fin, fout):
                    try:
                        if os.path.isfile(f): os.remove(f)
                    except Exception:
                        pass
            return

        # 合并整本 MP3：前端把各章 MP3 按 [count][len][bytes]... 打包发过来，ffmpeg concat 成一个文件
        if path == '/__mp3concat':
            if not tts_guest_ok(self):
                return self._json({'ok': False, 'error': '需要口令'}, 403)
            try:
                n = int(self.headers.get('Content-Length') or 0)
                raw = self.rfile.read(n)
            except Exception:
                raw = b''
            if len(raw) < 8:
                return self._json({'ok': False, 'error': 'empty'}, 400)
            ff = os.path.join(ROOT, 'tts-local', 'src', 'GPT-SoVITS-main', 'runtime', 'ffmpeg.exe')
            if not os.path.isfile(ff):
                return self._json({'ok': False, 'error': '没有 ffmpeg'}, 500)
            stamp = str(time.time_ns())
            files = []
            try:
                cnt = int.from_bytes(raw[0:4], 'little')
                off = 4
                if cnt <= 0 or cnt > 2000:
                    return self._json({'ok': False, 'error': '段数不对'}, 400)
                os.makedirs(TTS_DIR, exist_ok=True)
                for i in range(cnt):
                    ln = int.from_bytes(raw[off:off + 4], 'little')
                    off += 4
                    if ln <= 0 or off + ln > len(raw):
                        return self._json({'ok': False, 'error': '包体不完整'}, 400)
                    p = os.path.join(TTS_DIR, 'expc_%s_%04d.mp3' % (stamp, i))
                    with open(p, 'wb') as f:
                        f.write(raw[off:off + ln])
                    files.append(p)
                    off += ln
                lst = os.path.join(TTS_DIR, 'expc_%s.txt' % stamp)
                with open(lst, 'w', encoding='utf-8') as f:
                    for p in files:
                        f.write("file '%s'\n" % p.replace('\\', '/'))
                fout = os.path.join(TTS_DIR, 'expc_%s.mp3' % stamp)
                rf = subprocess.run([ff, '-y', '-hide_banner', '-loglevel', 'error',
                                     '-f', 'concat', '-safe', '0', '-i', lst,
                                     '-c', 'copy', fout],
                                    capture_output=True, timeout=600)
                if rf.returncode != 0 or not os.path.isfile(fout):
                    return self._json({'ok': False, 'error': '合并失败'}, 500)
                data = open(fout, 'rb').read()
                self.send_response(200)
                self.send_header('Content-Type', 'audio/mpeg')
                self.send_header('Content-Length', str(len(data)))
                self.send_header('Cache-Control', 'no-store')
                self.end_headers()
                self.wfile.write(data)
            finally:
                for p in files:
                    try:
                        if os.path.isfile(p): os.remove(p)
                    except Exception:
                        pass
                try:
                    lst2 = os.path.join(TTS_DIR, 'expc_%s.txt' % stamp)
                    if os.path.isfile(lst2): os.remove(lst2)
                    lst2b = os.path.join(TTS_DIR, 'expc_%s.mp3' % stamp)
                    if os.path.isfile(lst2b): os.remove(lst2b)
                except Exception:
                    pass
            return

        # 语音包：导入（参考音频 + 台词） / 删除
        if path == '/__voicepacks':
            if not self._loopback():
                return self._json({'ok': False, 'error': '语音包只允许电脑本机 / USB 连接管理'}, 403)
            data = self._body()
            name = str(data.get('name') or '').strip()[:40] or '未命名语音包'
            text = str(data.get('text') or '').strip()[:300]
            lang = str(data.get('lang') or 'ja').strip().lower()[:5]
            audio = str(data.get('audio') or '')
            ext = str(data.get('ext') or 'wav').strip().lower().lstrip('.')[:5]
            if ext not in ('wav', 'mp3', 'm4a', 'ogg', 'flac', 'aac'):
                ext = 'wav'
            if len(audio) < 2000:
                return self._json({'ok': False, 'error': '音频太小了'}, 400)
            try:
                raw = base64.b64decode(audio)
            except Exception:
                return self._json({'ok': False, 'error': '音频解码失败'}, 400)
            if len(raw) > 30 * 1024 * 1024:
                return self._json({'ok': False, 'error': '音频超过 30MB'}, 400)
            pid = 'p' + hashlib.md5((name + str(time.time())).encode('utf-8')).hexdigest()[:10]
            os.makedirs(PACK_DIR, exist_ok=True)
            fn = os.path.join(PACK_DIR, pid + '.' + ext)
            try:
                with open(fn, 'wb') as f:
                    f.write(raw)
            except Exception as e:
                return self._json({'ok': False, 'error': '保存失败：%s' % e}, 500)
            items = packs_load()
            items.append({'id': pid, 'name': name, 'text': text, 'lang': lang or 'ja',
                          'file': fn, 'at': time.time()})
            packs_save(items)
            return self._json({'ok': True, 'pack': {'id': pid, 'name': name}})

        if path == '/__voicepacks/del':
            if not self._loopback():
                return self._json({'ok': False, 'error': '语音包只允许电脑本机 / USB 连接管理'}, 403)
            data = self._body()
            pid = str(data.get('id') or '')
            items = packs_load()
            keep = []
            for it in items:
                if it.get('id') == pid:
                    try:
                        if it.get('file') and os.path.isfile(it['file']):
                            os.remove(it['file'])
                    except Exception:
                        pass
                else:
                    keep.append(it)
            packs_save(keep)
            return self._json({'ok': True})

        # 听书：合成一段文字（本地服务优先，其次 edge-tts，都带磁盘缓存）
        if path == '/__tts':
            if not tts_guest_ok(self):
                return self._json({'ok': False, 'error': '需要口令'}, 403)
            data = self._body()
            text = str(data.get('text') or '').strip()[:800]
            if not text:
                return self._json({'ok': False, 'error': 'empty'}, 400)
            lang = str(data.get('lang') or '').strip().lower()[:5]
            rate_s = str(data.get('rate') or '')[:8]
            engine = str(data.get('engine') or '').strip().lower()[:8]
            pack_id = str(data.get('pack') or '').strip()[:24]
            # 中枢开门时允许其它设备用这些语音包（手机借电脑算力合成）；关门则只给本机
            if pack_id and not self._loopback() and not hub_open():
                return self._json({'ok': False, 'error': '语音包只允许电脑本机 / USB 连接使用'}, 403)
            pack = packs_get(pack_id) if pack_id else None
            if pack:
                _want_local = True
            if lang not in ('zh', 'ja', 'en', 'ko', 'yue'):
                lang = ''

            # 1) 本地离线合成（tts-local.json）
            _cfg = tts_local_cfg() or {}
            _strict = bool(_cfg.get('strict_local'))
            _local_langs = _cfg.get('local_langs')
            _want_local = not (isinstance(_local_langs, list) and _local_langs and (lang or 'zh') not in _local_langs)
            if engine == 'edge':
                _want_local = False
            elif engine == 'local':
                _want_local = bool(_cfg)
            if _cfg and _want_local:
                try:
                    raw = None
                    for _attempt in range(3):
                        try:
                            with TTS_LOCAL_LOCK:
                                raw = tts_local(text, lang, rate_s, pack)
                            break
                        except Exception as _e1:
                            if _attempt < 2:
                                time.sleep(0.6)
                                continue
                            raise
                    if raw:
                        raw = _post_process_wav(raw, lowpass=not bool(pack_id))
                        _c = tts_local_cfg() or {}
                        _sig = hashlib.md5(json.dumps(_c.get('body') or {}, ensure_ascii=False,
                                                      sort_keys=True).encode('utf-8')).hexdigest()[:8]
                        _psig = '-'
                        if pack:
                            try:
                                _psig = str(int(os.path.getmtime(pack.get('file') or '')))
                            except Exception:
                                _psig = '-'
                        key = hashlib.md5(('local|pp1|' + (pack_id or '-') + '|' + _psig + '|' + lang + '|' + rate_s + '|' + _sig + '|' + text).encode('utf-8')).hexdigest()
                        ext, _mime = sniff_audio(raw)
                        os.makedirs(TTS_DIR, exist_ok=True)
                        fp = os.path.join(TTS_DIR, key + ext)
                        if not (os.path.isfile(fp) and os.path.getsize(fp) > 0):
                            tmp = fp + '.part'
                            with open(tmp, 'wb') as f:
                                f.write(raw)
                            os.replace(tmp, fp)
                        return self._json({'ok': True, 'url': '/__tts/%s%s' % (key, ext), 'local': True})
                except Exception as e:
                    tts_log('本地失败 lang=%s 文本长=%d md5=%s：%s' % (lang or 'zh', len(text), hashlib.md5(text.encode('utf-8')).hexdigest()[:8], str(e)[:80]))
                    if _strict:
                        return self._json({'ok': False, 'fallback': 'none',
                                           'error': '本地语音这次没合成出来'}, 503)
                    if time.time() < TTS_WARM_UNTIL:
                        return self._json({'ok': False, 'warming': True,
                                           'error': '本地语音正在加载'}, 503)
                    pass  # 本地服务挂了就退回在线

            if _cfg and _want_local:
                tts_log('本地无返回，回落在线 lang=%s' % (lang or 'zh'))
            if not _edge:
                return self._json({
                    'ok': False, 'fallback': 'browser',
                    'error': '服务端没装 edge-tts（pip install edge-tts）',
                }, 503)
            _r = str(data.get('rate') or '').strip()
            if _r and not _r.endswith('%'):
                try:
                    _n = int(float(_r))
                    _r = ('+%d%%' % _n) if _n >= 0 else ('%d%%' % _n)
                except Exception:
                    _r = TTS_RATE
            rate_req = _r or TTS_RATE
            voice = str(data.get('voice') or '')[:64]
            if voice == 'local':
                voice = ''
            if not voice:
                voice = 'ja-JP-NanamiNeural' if lang == 'ja' else (
                    'en-US-AriaNeural' if lang == 'en' else TTS_VOICE)
            rate = rate_req[:12]
            pitch = str(data.get('pitch') or TTS_PITCH)[:12]
            try:
                key, hit = tts_make(text, voice, rate, pitch)
            except Exception as e:
                return self._json({'ok': False, 'fallback': 'browser', 'error': str(e)}, 503)
            return self._json({
                'ok': True,
                'url': '/__tts/%s.mp3' % key,
                'cached': hit,
            })

        if path == '/__lan/announce':
            data = self._body()
            pid = str(data.get('id') or '')
            if not pid:
                return self._json({'error': 'bad request'}, 400)
            with SIGNAL_LOCK:
                LAN_PEERS[pid] = {
                    'id': pid,
                    'name': str(data.get('name') or ''),
                    'avatar': str(data.get('avatar') or ''),
                    'ip': self.client_address[0] if self.client_address else '',
                    'at': time.time(),
                }
            return self._json({'ok': True})

        if path == '/__lan/ack':
            data = self._body()
            frm = data.get('from') or {}
            to = str(data.get('to') or '')
            if not to or not frm.get('id'):
                return self._json({'error': 'bad request'}, 400)
            with SIGNAL_LOCK:
                LAN_HI.setdefault(to, []).append({
                    'kind': 'ack',
                    'accept': bool(data.get('accept')),
                    'card': str(data.get('card') or '')[:4000],
                    'from': {'id': str(frm.get('id')), 'name': str(frm.get('name') or ''),
                             'avatar': str(frm.get('avatar') or '')},
                    'at': time.time(),
                })
            return self._json({'ok': True})

        if path == '/__lan/sig':
            data = self._body()
            to = str(data.get('to') or '')
            frm = str(data.get('from') or '')
            if not to or not frm:
                return self._json({'error': 'bad request'}, 400)
            with SIGNAL_LOCK:
                LAN_SIG.setdefault(to, []).append({
                    'from': frm,
                    'kind': str(data.get('kind') or 'offer'),
                    'sdp': str(data.get('sdp') or ''),
                    'sigId': str(data.get('sigId') or ''),
                    'at': time.time(),
                })
                if len(LAN_SIG[to]) > 20:
                    LAN_SIG[to] = LAN_SIG[to][-20:]
            return self._json({'ok': True})

        if path == '/__lan/hi':
            data = self._body()
            frm = data.get('from') or {}
            to = str(data.get('to') or '')
            if not to or not frm.get('id'):
                return self._json({'error': 'bad request'}, 400)
            with SIGNAL_LOCK:
                LAN_HI.setdefault(to, []).append({
                    'kind': 'req',
                    'card': str(data.get('card') or '')[:4000],
                    'from': {'id': str(frm.get('id')), 'name': str(frm.get('name') or ''),
                             'avatar': str(frm.get('avatar') or '')},
                    'at': time.time(),
                })
                if len(LAN_HI[to]) > 40:
                    LAN_HI[to] = LAN_HI[to][-40:]
                # 防止被刷出一堆收件箱键（每个键只活 10 分钟，这里再兜一层）
                if len(LAN_HI) > 3000:
                    for _k in list(LAN_HI.keys())[:1000]:
                        if _k != to:
                            LAN_HI.pop(_k, None)
                if len(LAN_HI[to]) > 50:
                    LAN_HI[to] = LAN_HI[to][-50:]
            return self._json({'ok': True})


        if path == '/__signal/new':
            try:
                return self._json({'code': _new_code()})
            except Exception as e:
                return self._json({'error': str(e)}, 500)

        if path == '/__signal/put':
            data = self._body()
            code = str(data.get('code', ''))
            kind = data.get('kind')
            sdp = data.get('sdp', '')
            if kind not in ('offer', 'answer') or not code or not sdp:
                return self._json({'error': 'bad request'}, 400)
            with SIGNAL_LOCK:
                room = SIGNAL.get(code)
                if room is None:
                    room = SIGNAL[code] = {'offer': None, 'answer': None, 'at': time.time()}
                room[kind] = sdp
                room['at'] = time.time()
            return self._json({'ok': True})

        if path == '/__blind/put':
            if not _blind_allow(self.client_address[0], BLIND_PUT_PER_MIN, time.time()):
                return self._json({'error': 'rate limited'}, 429)
            data = self._body()
            room = str(data.get('room', ''))
            role = data.get('role')
            payload = str(data.get('payload', ''))
            nonce = str(data.get('nonce', ''))[:64]
            if role not in ('offer', 'answer') or not re.fullmatch(r'[0-9a-fA-F]{16,64}', room) or not payload:
                return self._json({'error': 'bad request'}, 400)
            if len(payload) > BLIND_MAX_PAYLOAD:
                return self._json({'error': 'payload too large'}, 413)
            now = time.time()
            with BLIND_LOCK:
                _blind_sweep(now)
                rec = BLIND.get(room)
                if rec is None:
                    if len(BLIND) >= BLIND_MAX_ROOMS:
                        return self._json({'error': 'hub full'}, 503)
                    rec = BLIND[room] = {'offer': [], 'answer': [], 'at': now}
                lst = rec[role]
                lst.append({'payload': payload, 'nonce': nonce, 'at': now})
                if len(lst) > BLIND_MAX_PER:
                    del lst[:len(lst) - BLIND_MAX_PER]
                rec['at'] = now
            return self._json({'ok': True})

        if path == '/__result':
            try:
                tag = self._query().get('tag', '')
                n = int(self.headers.get('Content-Length') or 0)
                data = self.rfile.read(n)
                name = 'result-%s.json' % tag if tag else 'result.json'
                with open(os.path.join(ROOT, name), 'wb') as f:
                    f.write(data)
                self.send_response(204)
                self.end_headers()
            except Exception:
                self.send_response(500)
                self.end_headers()
            return

        self.send_response(404)
        self.end_headers()


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True
    address_family = socket.AF_INET6

    def server_bind(self):
        # 关掉 V6ONLY，让同一个 socket 也接受 IPv4（127.0.0.1 才不会失效）
        try:
            self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
        except Exception:
            pass
        super().server_bind()


def open_later(url):
    try:
        webbrowser.open(url)
    except Exception:
        pass


def adb_reverse_loop():
    """数据线连接：手机插在电脑上时，把电脑的 8642 反代到手机的 127.0.0.1:8650。
    这样 App 里选「数据线连接电脑」就走 USB，不需要同一 Wi-Fi。"""
    adb = None
    try:
        import shutil as _sh
        adb = _sh.which('adb')
    except Exception:
        adb = None
    if not adb:
        for _p in (r'E:\_android\sdk\platform-tools\adb.exe',
                   os.path.expandvars(r'%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe'),
                   r'C:\Android\platform-tools\adb.exe'):
            if os.path.exists(_p):
                adb = _p
                break
    if not adb:
        return
    import subprocess as _sp
    while True:
        try:
            _sp.run([adb, 'reverse', 'tcp:8650', 'tcp:8642'],
                    stdout=_sp.DEVNULL, stderr=_sp.DEVNULL, timeout=8)
        except Exception:
            pass
        time.sleep(8)


PUBLIC_PROC = None
PUBLIC_URL = ''
PUBLIC_LOCK = threading.Lock()


def _cf_exe_path():
    for c in (os.path.join(ROOT, 'cloudflared.exe'),
              os.path.join(os.path.dirname(sys.executable), 'cloudflared.exe'),
              os.path.join(ROOT, '工具', 'cloudflared.exe')):
        if os.path.isfile(c):
            return c
    return ''


def public_tunnel_state():
    return {'on': bool(PUBLIC_PROC and PUBLIC_PROC.poll() is None), 'url': PUBLIC_URL}


def public_tunnel_stop():
    global PUBLIC_PROC, PUBLIC_URL
    with PUBLIC_LOCK:
        p = PUBLIC_PROC
        PUBLIC_PROC = None
        PUBLIC_URL = ''
    if p:
        try:
            p.terminate()
        except Exception:
            pass
    return True


def public_tunnel_start():
    """起 cloudflared 快速隧道，返回 (ok, url)"""
    global PUBLIC_PROC, PUBLIC_URL
    with PUBLIC_LOCK:
        if PUBLIC_PROC and PUBLIC_PROC.poll() is None and PUBLIC_URL:
            return True, PUBLIC_URL
        exe = _cf_exe_path()
        if not exe:
            return False, '没找到 cloudflared.exe'
        try:
            p = subprocess.Popen([exe, 'tunnel', '--url', 'http://127.0.0.1:%d' % PORT, '--no-autoupdate'],
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        except Exception as e:
            return False, str(e)
        url = ''
        try:
            for _ in range(80):
                line = p.stdout.readline()
                if not line:
                    break
                m = re.findall(r'https://[a-z0-9-]+\.trycloudflare\.com', line or '')
                if m:
                    url = m[0]
                    break
        except Exception:
            pass
        PUBLIC_PROC = p
        PUBLIC_URL = url
        def _drain():
            try:
                for _ in p.stdout:
                    pass
            except Exception:
                pass
        threading.Thread(target=_drain, daemon=True).start()
        try:
            open(os.path.join(HUB_DATA_DIR, 'public-url.txt'), 'w', encoding='utf-8').write(url)
        except Exception:
            pass
        return (bool(url), url or '没拿到地址')


def start_public_tunnel():
    """起 cloudflared 快速隧道，把公网地址打印出来（--public 用）"""
    _cands = [
        os.path.join(ROOT, 'cloudflared.exe'),
        os.path.join(os.path.dirname(sys.executable), 'cloudflared.exe'),
        os.path.join(ROOT, '工具', 'cloudflared.exe'),
    ]
    exe = next((c for c in _cands if os.path.isfile(c)), '')
    if not os.path.isfile(exe):
        print('  公网通道：没找到 cloudflared.exe')
        return None
    try:
        p = subprocess.Popen([exe, 'tunnel', '--url', 'http://127.0.0.1:%d' % PORT, '--no-autoupdate'],
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    except Exception as e:
        print('  公网通道启动失败：%s' % e)
        return None

    def _reader():
        import re as _re
        try:
            for line in p.stdout:
                m = _re.findall(r'https://[a-z0-9-]+\.trycloudflare\.com', line or '')
                if m:
                    url = m[0]
                    print('')
                    print('  公网地址:   ' + url)
                    print('')
                    try:
                        open(os.path.join(HUB_DATA_DIR, 'public-url.txt'), 'w', encoding='utf-8').write(url)
                    except Exception:
                        pass
                    break
        except Exception:
            pass
    threading.Thread(target=_reader, daemon=True).start()
    return p


if __name__ == "__main__":
    # 本地语音（9880）已迁出：不再随中枢启动，改由「启动语音.bat」独立管理
    rooms_load()
    try:
        threading.Thread(target=lan_beacon_loop, daemon=True).start()
    except Exception:
        pass
    try:
        threading.Thread(target=adb_reverse_loop, daemon=True).start()
    except Exception:
        pass
    ip = lan_ip()
    v6 = global_ipv6()
    # 手机上（Termux）不一定能绑 IPv6，失败就退回 IPv4
    try:
        _srv = Server(("::", PORT), Handler)
    except OSError:
        class _Server4(socketserver.ThreadingTCPServer):
            allow_reuse_address = True
            daemon_threads = True
        BIND_ADDR = (os.environ.get('HLJJS_BIND') or '').strip() or '0.0.0.0'
        _srv = _Server4((BIND_ADDR, PORT), Handler)
    with _srv as httpd:
        _cert = (os.environ.get('HLJJS_HTTPS_CERT') or '').strip()
        _key = (os.environ.get('HLJJS_HTTPS_KEY') or '').strip()
        if _cert and _key and os.path.isfile(_cert) and os.path.isfile(_key):
            try:
                import ssl as _ssl
                _ctx = _ssl.SSLContext(_ssl.PROTOCOL_TLS_SERVER)
                _ctx.load_cert_chain(_cert, _key)
                httpd.socket = _ctx.wrap_socket(httpd.socket, server_side=True)
                print("  HTTPS 已开启（证书 %s）" % _cert)
                print("  本机打开:   https://127.0.0.1:%d/" % PORT)
            except Exception as _e:
                print("  HTTPS 开启失败：%s" % _e)
        if not os.environ.get('HLJJS_NO_BROWSER'):
            threading.Timer(0.7, lambda: open_later("http://127.0.0.1:%d/" % PORT)).start()
        if '--public' in sys.argv:
            threading.Timer(1.2, start_public_tunnel).start()
        print("")
        print("  互联网精神与世长存  已启动")
        print("")
        print("  本机打开:   http://127.0.0.1:%d/" % PORT)
        print("  同一 Wi-Fi: http://%s:%d/" % (ip, PORT))
        if _bbs:
            print("  社区（论坛/资源/联机）: http://127.0.0.1:%d/bbs" % PORT)
        if v6:
            print("  公网 IPv6:  http://[%s]:%d/" % (v6, PORT))
            print("              （手机有 IPv6 时，不同网络也能用这个）")
        else:
            print("  公网 IPv6:  没有")
        print("")
        print("  第一次运行 Windows 可能会弹防火墙提示，请选「允许」。")
        print("")
        print("  「社团码」模式需要这个窗口开着（它只帮忙交换一次地址）。")
        print("  纯手动暗号 / 扫码模式不需要它。")
        print("")
        print("  关闭此窗口即停止。")
        print("")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            pass
