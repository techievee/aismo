"""A tiny fake MMSC for emulator tests. Serves one m-retrieve-conf and accepts m-send-req / m-notifyresp-ind.
Run: python3 -I fake_mmsc.py PORT LOGDIR     (the emulator reaches the host as 10.0.2.2)
Its PDU reader is written independently of the app's Kotlin codec on purpose."""
import json
import struct
import sys
import time
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]); LOG = sys.argv[2]
EVENTS = LOG + "/events.jsonl"


def log(**kw):
    kw["t"] = time.strftime("%H:%M:%S")
    with open(EVENTS, "a") as f:
        f.write(json.dumps(kw) + "\n")
    print(kw, flush=True)


def png(w=320, h=240):
    raw = b"".join(b"\x00" + bytes(((x * 255) // w, (y * 255) // h, 160) [i] for x in range(w) for i in range(3)) for y in range(h))
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


def uv(n):
    g = []
    while True:
        g.append(n & 0x7F); n >>= 7
        if not n: break
    return bytes((g[i] | (0x80 if i else 0)) for i in reversed(range(len(g))))


def z(s): return s.encode() + b"\0"


def retrieve_conf():
    ct = bytes([0x80 | 0x33, 0x89]) + z("application/smil") + bytes([0x8A]) + z("<0000>")
    text = "Hello from the fake MMSC ✓".encode()
    h1 = bytes([0x03, 0x83, 0x81, 0xEA, 0x8E]) + z("text_0.txt") + bytes([0xC0, 0x22]) + z("<text_0>")
    img = png()
    h2 = bytes([0xA0, 0x8E]) + z("scene.png") + bytes([0xC0, 0x22]) + z("<scene>")   # 0xA0 = 0x20|0x80 = image/png
    frm = z("+61411000999/TYPE=PLMN")
    return (bytes([0x8C, 0x84, 0x98]) + z("fake-retrieve-1") + bytes([0x8D, 0x92, 0x89, 1 + len(frm), 0x80]) + frm
            + bytes([0x97]) + z("+61411999000/TYPE=PLMN") + bytes([0x96]) + z("Greetings") + bytes([0x85, 0x04, 0x65, 0x5B, 0x3E, 0x80])
            + bytes([0x84, len(ct)]) + ct + uv(2) + uv(len(h1)) + uv(len(text)) + h1 + text + uv(len(h2)) + uv(len(img)) + h2 + img)


class R:
    def __init__(s, b): s.b = b; s.p = 0
    def peek(s): return s.b[s.p]
    def nx(s): s.p += 1; return s.b[s.p - 1]
    def uvar(s):
        v = 0
        while True:
            c = s.nx(); v = (v << 7) | (c & 0x7F)
            if not c & 0x80: return v
    def vlen(s):
        c = s.nx()
        return c if c <= 30 else s.uvar()
    def text(s):
        if s.peek() == 0x7F: s.p += 1
        e = s.b.index(0, s.p); r = s.b[s.p:e].decode("utf-8", "replace"); s.p = e + 1; return r
    def enc(s):
        if s.peek() > 30: return s.text()
        n = s.vlen(); end = s.p + n; s.nx(); r = s.text(); s.p = end; return r


def describe_send_req(b):
    r = R(b); out = {"type": None, "tid": None, "to": [], "subject": None, "parts": []}
    while r.p < len(b):
        f = r.nx()
        if f == 0x8C: out["type"] = hex(r.nx())
        elif f == 0x98: out["tid"] = r.text()
        elif f == 0x8D: r.nx()
        elif f == 0x85:
            n = r.nx(); r.p += n
        elif f == 0x89: n = r.vlen(); r.p += n
        elif f in (0x97, 0x82, 0x81): out["to"].append(r.enc())
        elif f == 0x96: out["subject"] = r.enc()
        elif f in (0x8A, 0x8F, 0x86, 0x90): r.nx()
        elif f == 0x84:
            n = r.vlen(); r.p += n
            cnt = r.uvar()
            for _ in range(cnt):
                hl = r.uvar(); dl = r.uvar(); hs = r.p
                c = r.peek(); ctype = None
                if c >= 0x80: r.p += 1; ctype = {0x83: "text/plain", 0x9E: "image/jpeg", 0xA0: "image/png", 0x9D: "image/gif"}.get(c & 0xFF, "wk-%x" % c)
                elif c > 30: ctype = r.text()
                else:
                    n2 = r.vlen(); e2 = r.p + n2
                    ctype = {0x83: "text/plain", 0x9E: "image/jpeg", 0xA0: "image/png"}.get(r.peek(), None) if r.peek() >= 0x80 else r.text()
                    if r.peek() >= 0x80 and ctype: r.p += 1
                    r.p = e2
                name = None
                while r.p < hs + hl:
                    h = r.nx()
                    if h == 0x8E: name = r.text()
                    elif h == 0xC0:
                        if r.peek() == 0x22: r.p += 1
                        r.text()
                    else: break
                r.p = hs + hl
                out["parts"].append({"type": ctype, "name": name, "bytes": dl}); r.p += dl
            break
    return out


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def log_message(self, *a): pass

    def do_GET(self):
        body = retrieve_conf()
        log(ev="GET", path=self.path, ua=self.headers.get("User-Agent"), sent=len(body))
        self.send_response(200); self.send_header("Content-Type", "application/vnd.wap.mms-message"); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0)); b = self.rfile.read(n)
        open(f"{LOG}/post-{int(time.time()*1000)}.bin", "wb").write(b)
        mtype = b[1] if len(b) > 1 and b[0] == 0x8C else None
        if mtype == 0x80:
            d = describe_send_req(b); log(ev="POST m-send-req", path=self.path, length=n, **d)
            tid = d["tid"] or "x"
            resp = bytes([0x8C, 0x81, 0x98]) + z(tid) + bytes([0x8D, 0x92, 0x92, 0x80, 0x8B]) + z("FAKE-MSG-ID-1")
        else:
            log(ev="POST other", path=self.path, length=n, mms_type=hex(mtype) if mtype is not None else None, hex=b[:40].hex())
            resp = b""
        self.send_response(200); self.send_header("Content-Type", "application/vnd.wap.mms-message"); self.send_header("Content-Length", str(len(resp))); self.end_headers(); self.wfile.write(resp)


ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
