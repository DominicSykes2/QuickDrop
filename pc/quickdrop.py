#!/usr/bin/env python3
"""QuickDrop - send files between your phone and PC over Wi-Fi.

(c) 2026 Dominic Sykes. All rights reserved.

One file, standard library only. Runs on Windows / Mac / Linux and on Android
(Termux or Pydroid 3). Same protocol as the original Quick Drop Sender/Receiver
scripts, so old and new versions can talk to each other.

    python quickdrop.py                        menu (receive + send in one window)
    python quickdrop.py receive [--pin 1234]   only receive
    python quickdrop.py send a.jpg b.pdf       send (finds the other device itself)
    python quickdrop.py send a.jpg --to 192.168.1.42
    python quickdrop.py web                    browser mode: the phone needs NO app
    python quickdrop.py scan                   list devices running QuickDrop
"""
import argparse
import hmac
import json
import os
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, unquote, urlparse

VERSION = "2.0"
COPYRIGHT = "(c) 2026 Dominic Sykes. All rights reserved."
PORT = 5005            # file transfer (TCP)
DISCOVERY_PORT = 5006  # lets devices find each other (UDP)
WEB_PORT = 8080        # browser mode
CHUNK = 64 * 1024

_say_lock = threading.Lock()


def say(*a, **k):
    with _say_lock:
        print(*a, **k)
        sys.stdout.flush()


# ----------------------------------------------------------------- helpers

def local_ips():
    """Every IPv4 address of this device that looks like a LAN address."""
    ips = []
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # no packet is actually sent
        ips.append(s.getsockname()[0])
    except OSError:
        pass
    finally:
        s.close()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except OSError:
        pass
    return ips or ["127.0.0.1"]


def local_ip():
    return local_ips()[0]


def default_folder():
    for base in ("/storage/emulated/0/Download", "/sdcard/Download"):
        if os.path.isdir(base) and os.access(base, os.W_OK):
            return Path(base) / "QuickDrop"
    dl = Path.home() / "Downloads"
    return (dl if dl.is_dir() else Path.home()) / "QuickDrop"


def safe_name(name):
    """Strip any folder parts so a sender can't write outside the save folder."""
    name = os.path.basename(str(name).replace("\\", "/")).strip().strip(".")
    bad = '<>:"/\\|?*'
    name = "".join(c for c in name if c not in bad and ord(c) >= 32)
    return name or "file"


def unique_path(folder, name):
    path = Path(folder) / name
    i = 1
    while path.exists():
        path = Path(folder) / f"{Path(name).stem} ({i}){Path(name).suffix}"
        i += 1
    return path


def human(n):
    n = float(n)
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{int(n)} B" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024


def recv_line(conn, limit=1_000_000):
    buf = bytearray()
    while True:
        ch = conn.recv(1)
        if not ch:
            raise ConnectionError("connection closed")
        if ch == b"\n":
            return bytes(buf)
        buf += ch
        if len(buf) > limit:
            raise ValueError("header too large")


def clean_path(text):
    return text.strip().strip('"').strip("'")


# ---------------------------------------------------------------- receiving

class Receiver:
    """Accepts files over TCP and answers discovery broadcasts."""

    def __init__(self, folder, pin="", port=PORT, discovery_port=DISCOVERY_PORT, show_progress=True):
        self.folder = Path(folder).expanduser()
        self.pin = pin
        self.port = port
        self.discovery_port = discovery_port
        self.show_progress = show_progress
        self.server = None
        self.udp = None
        self.running = False

    def start(self):
        self.folder.mkdir(parents=True, exist_ok=True)
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("", self.port))  # raises OSError if the port is busy
        srv.listen(5)
        self.server = srv
        self.running = True
        threading.Thread(target=self._accept_loop, daemon=True).start()
        threading.Thread(target=self._discovery_loop, daemon=True).start()

    def stop(self):
        self.running = False
        for s in (self.server, self.udp):
            try:
                if s:
                    s.close()
            except OSError:
                pass

    def _accept_loop(self):
        while self.running:
            try:
                conn, addr = self.server.accept()
            except OSError:
                break
            threading.Thread(target=self._handle, args=(conn, addr), daemon=True).start()

    def _discovery_loop(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind(("", self.discovery_port))
        except OSError:
            s.close()
            return  # auto-discovery unavailable; typing the IP still works
        self.udp = s
        reply = f"QUICKDROP!{socket.gethostname()}".encode()
        while self.running:
            try:
                data, addr = s.recvfrom(1024)
            except OSError:
                break
            if data.strip() == b"QUICKDROP?":
                try:
                    s.sendto(reply, addr)
                except OSError:
                    pass

    def _handle(self, conn, addr):
        with conn:
            conn.settimeout(30)
            part = None
            try:
                header = json.loads(recv_line(conn))
                if self.pin and not hmac.compare_digest(str(header.get("pin", "")), self.pin):
                    conn.sendall(b"ERR wrong PIN\n")
                    say(f"[!] {addr[0]} tried a wrong PIN")
                    return
                files = header["files"]
                conn.sendall(b"OK\n")
                say(f"[+] {addr[0]} is sending {len(files)} file(s)")
                for f in files:
                    name, size = safe_name(f["name"]), int(f["size"])
                    dest = unique_path(self.folder, name)
                    part = dest.with_name(dest.name + ".part")
                    got = 0
                    last_pct = -1
                    with open(part, "wb") as out:
                        while got < size:
                            data = conn.recv(min(CHUNK, size - got))
                            if not data:
                                raise ConnectionError("connection lost mid-file")
                            out.write(data)
                            got += len(data)
                            pct = 100 if size == 0 else got * 100 // size
                            if self.show_progress and pct != last_pct:
                                last_pct = pct
                                with _say_lock:
                                    print(f"\r    {dest.name}: {pct}% ({human(got)}/{human(size)})", end="", flush=True)
                    part.replace(dest)
                    part = None
                    if self.show_progress:
                        say()
                    say(f"    saved -> {dest}")
                conn.sendall(b"DONE\n")
            except Exception as e:  # noqa: BLE001
                say(f"\n[!] transfer from {addr[0]} failed: {e}")
                if part and part.exists():
                    try:
                        part.unlink()
                    except OSError:
                        pass


# ------------------------------------------------------------------ sending

def broadcast_targets(port):
    targets = [("255.255.255.255", port)]
    for ip in local_ips():
        parts = ip.split(".")
        if len(parts) == 4 and not ip.startswith("127."):
            targets.append((".".join(parts[:3] + ["255"]), port))  # typical /24 home network
    return targets


def scan(seconds=2.0, extra_targets=(), discovery_port=DISCOVERY_PORT):
    """Find other QuickDrop devices. Returns a list of (ip, device_name)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.settimeout(0.4)
    mine = set(local_ips())
    found = {}
    targets = broadcast_targets(discovery_port) + list(extra_targets)

    def ping():
        for t in targets:
            try:
                s.sendto(b"QUICKDROP?", t)
            except OSError:
                pass

    end = time.time() + seconds
    try:
        ping()
        while time.time() < end:
            try:
                data, addr = s.recvfrom(1024)
            except socket.timeout:
                ping()
                continue
            except OSError:
                break
            if data.startswith(b"QUICKDROP!") and (addr[0] not in mine or addr[0].startswith("127.")):
                found[addr[0]] = data[len(b"QUICKDROP!"):].decode(errors="replace")
    finally:
        s.close()
    return list(found.items())


def send_files(host, files, pin="", port=PORT, show_progress=True):
    files = [Path(f) for f in files]
    header = {"pin": pin, "files": [{"name": f.name, "size": f.stat().st_size} for f in files]}
    try:
        s = socket.create_connection((host, port), timeout=10)
    except OSError as e:
        raise ConnectionError(
            f"could not connect to {host}:{port} ({e}). Is QuickDrop running there, are both devices "
            f"on the same Wi-Fi, and does the firewall allow Python on private networks?") from None
    with s:
        s.settimeout(60)
        s.sendall(json.dumps(header).encode() + b"\n")
        reply = recv_line(s, 65536).decode().strip()
        if reply != "OK":
            raise RuntimeError(reply.replace("ERR ", "receiver refused: ") or "receiver refused")
        for f in files:
            size = f.stat().st_size
            sent = 0
            last_pct = -1
            with open(f, "rb") as fh:
                while True:
                    chunk = fh.read(CHUNK)
                    if not chunk:
                        break
                    s.sendall(chunk)
                    sent += len(chunk)
                    pct = 100 if size == 0 else sent * 100 // size
                    if show_progress and pct != last_pct:
                        last_pct = pct
                        with _say_lock:
                            print(f"\r  {f.name}: {pct}% ({human(sent)}/{human(size)})", end="", flush=True)
            if show_progress:
                say()
        done = recv_line(s, 65536).decode().strip()
        if done != "DONE":
            raise RuntimeError("the other device did not confirm the transfer")


def pick_receiver():
    say("Looking for devices on your Wi-Fi...")
    found = scan()
    if len(found) == 1:
        ip, name = found[0]
        say(f"Found {name} ({ip})")
        return ip
    if len(found) > 1:
        for i, (ip, name) in enumerate(found, 1):
            say(f"  {i}. {name} ({ip})")
        choice = input("Pick a number (or type an IP): ").strip()
        if choice.isdigit() and 1 <= int(choice) <= len(found):
            return found[int(choice) - 1][0]
        return choice
    say("No device found automatically (is QuickDrop open on the other device?).")
    return input("Type the other device's IP address: ").strip()


def collect_files(paths):
    files = []
    for p in paths:
        path = Path(clean_path(p)).expanduser()
        if path.is_file():
            files.append(path)
        else:
            say(f"  skipping (not a file): {p}")
    return files


# ------------------------------------------------------------- browser mode

PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>QuickDrop</title>
<style>
 body{font:16px/1.5 system-ui,sans-serif;margin:0;padding:16px;max-width:640px;margin:auto;background:#f4f7f8;color:#1c2a30}
 h1{margin:0 0 4px} section{background:#fff;border:1px solid #d9e2e6;border-radius:14px;padding:14px 16px;margin:14px 0}
 button{font:inherit;font-weight:600;min-height:44px;padding:8px 18px;border-radius:10px;border:0;background:#1d6a86;color:#fff}
 input[type=file],input[type=text]{font:inherit;width:100%;margin:6px 0}
 .row{margin:8px 0} .bar{height:10px;background:#e3ebee;border-radius:6px;overflow:hidden}
 .bar>i{display:block;height:100%;width:0;background:#1d6a86} .muted{color:#566871;font-size:.9rem}
 a{color:#1d6a86;font-weight:600} footer{text-align:center;color:#566871;font-size:.85rem;margin-top:20px}
</style></head><body>
<h1>QuickDrop</h1><p class="muted">__HOST__</p>
<section id="pinbox" __PINHIDE__><label>PIN<input type="text" id="pin" inputmode="numeric" autocomplete="off"></label></section>
<section><h2>Send to this PC</h2>
 <input type="file" id="f" multiple><button id="send">Send</button><div id="log"></div></section>
<section><h2>Get from this PC</h2><p class="muted">Files in: __SENDDIR__</p>
 <button id="refresh">Refresh list</button><div id="list"></div></section>
<footer>QuickDrop &copy; 2026 Dominic Sykes. All rights reserved.</footer>
<script>
const $=id=>document.getElementById(id);
const pin=()=>($('pin')?$('pin').value.trim():'');
function row(t){const d=document.createElement('div');d.className='row';const s=document.createElement('div');s.textContent=t;
 const b=document.createElement('div');b.className='bar';const i=document.createElement('i');b.appendChild(i);d.append(s,b);$('log').appendChild(d);return {s,i,t};}
function up(file){return new Promise((res,rej)=>{const r=row(file.name);const x=new XMLHttpRequest();
 x.open('POST','/upload?name='+encodeURIComponent(file.name));x.setRequestHeader('X-Pin',pin());
 x.upload.onprogress=e=>{if(e.lengthComputable)r.i.style.width=(100*e.loaded/e.total)+'%';};
 x.onload=()=>{if(x.status===200){r.i.style.width='100%';r.s.textContent=r.t+' - sent';res();}
  else{r.s.textContent=r.t+' - failed ('+(x.status===401?'wrong PIN':x.status)+')';rej();}};
 x.onerror=()=>{r.s.textContent=r.t+' - connection lost';rej();};x.send(file);});}
$('send').onclick=async()=>{const fs=[...$('f').files];if(!fs.length){alert('Choose files first');return;}
 for(const f of fs){try{await up(f);}catch(e){}}};
async function load(){const l=$('list');l.textContent='Loading...';
 try{const r=await fetch('/files',{headers:{'X-Pin':pin()}});
  if(r.status===401){l.textContent='Wrong PIN';return;}
  const a=await r.json();l.textContent='';if(!a.length){l.textContent='Nothing to download yet.';return;}
  a.forEach(f=>{const d=document.createElement('div');d.className='row';const k=document.createElement('a');
   k.textContent=f.name+'  ('+f.size_text+')';k.href='/download?name='+encodeURIComponent(f.name)+'&pin='+encodeURIComponent(pin());
   k.setAttribute('download',f.name);d.appendChild(k);l.appendChild(d);});
 }catch(e){l.textContent='Could not load the list.';}}
$('refresh').onclick=load;load();
</script></body></html>
"""


class WebServer:
    """Browser mode: a phone opens a page, uploads files here and downloads files from here."""

    def __init__(self, receive_folder, send_folder, pin="", port=WEB_PORT):
        self.receive_folder = Path(receive_folder).expanduser()
        self.send_folder = Path(send_folder).expanduser()
        self.pin = pin
        self.port = port
        self.httpd = None

    def start(self):
        self.receive_folder.mkdir(parents=True, exist_ok=True)
        self.send_folder.mkdir(parents=True, exist_ok=True)
        outer = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _reply(self, code, body=b"", ctype="text/plain; charset=utf-8", extra=None):
                self.send_response(code)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body)))
                for k, v in (extra or {}).items():
                    self.send_header(k, v)
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(body)

            def _authed(self, query):
                if not outer.pin:
                    return True
                given = self.headers.get("X-Pin") or (query.get("pin") or [""])[0]
                if hmac.compare_digest(str(given), outer.pin):
                    return True
                time.sleep(0.5)
                self._reply(401, b"wrong PIN")
                return False

            def do_GET(self):
                url = urlparse(self.path)
                q = parse_qs(url.query)
                if url.path == "/":
                    host = f"{socket.gethostname()}  ({local_ip()}:{outer.port})"
                    html = (PAGE.replace("__HOST__", host.replace("<", "&lt;"))
                            .replace("__SENDDIR__", str(outer.send_folder).replace("<", "&lt;"))
                            .replace("__PINHIDE__", "" if outer.pin else "hidden"))
                    return self._reply(200, html.encode(), "text/html; charset=utf-8")
                if not self._authed(q):
                    return
                if url.path == "/files":
                    items = []
                    for p in sorted(outer.send_folder.iterdir()):
                        if p.is_file() and not p.name.endswith(".part"):
                            items.append({"name": p.name, "size": p.stat().st_size,
                                          "size_text": human(p.stat().st_size)})
                    return self._reply(200, json.dumps(items).encode(), "application/json")
                if url.path == "/download":
                    name = safe_name((q.get("name") or [""])[0])
                    p = outer.send_folder / name
                    if not p.is_file():
                        return self._reply(404, b"not found")
                    size = p.stat().st_size
                    self.send_response(200)
                    self.send_header("Content-Type", "application/octet-stream")
                    self.send_header("Content-Length", str(size))
                    self.send_header("Content-Disposition", f"attachment; filename*=UTF-8''{quote(p.name)}")
                    self.end_headers()
                    with open(p, "rb") as fh:
                        while True:
                            chunk = fh.read(CHUNK)
                            if not chunk:
                                break
                            self.wfile.write(chunk)
                    say(f"[+] {self.client_address[0]} downloaded {p.name}")
                    return
                self._reply(404, b"not found")

            def do_POST(self):
                url = urlparse(self.path)
                q = parse_qs(url.query)
                if url.path != "/upload":
                    return self._reply(404, b"not found")
                if not self._authed(q):
                    return
                name = safe_name(unquote((q.get("name") or ["file"])[0]))
                dest = unique_path(outer.receive_folder, name)
                part = dest.with_name(dest.name + ".part")
                remaining = int(self.headers.get("Content-Length", "0"))
                try:
                    with open(part, "wb") as out:
                        while remaining:
                            data = self.rfile.read(min(CHUNK, remaining))
                            if not data:
                                raise ConnectionError("connection lost mid-file")
                            out.write(data)
                            remaining -= len(data)
                    part.replace(dest)
                except Exception as e:  # noqa: BLE001
                    say(f"[!] upload from {self.client_address[0]} failed: {e}")
                    try:
                        part.unlink()
                    except OSError:
                        pass
                    return
                say(f"[+] {self.client_address[0]} sent {dest.name} ({human(dest.stat().st_size)}) -> {dest}")
                self._reply(200, b"ok")

        ThreadingHTTPServer.daemon_threads = True
        self.httpd = ThreadingHTTPServer(("", self.port), H)
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def stop(self):
        if self.httpd:
            self.httpd.shutdown()
            self.httpd.server_close()


# --------------------------------------------------------------------- CLI

def banner():
    say(f"QuickDrop {VERSION}   {COPYRIGHT}")


def show_ready(folder, pin, port=PORT):
    say("Ready to receive.")
    for ip in local_ips():
        say(f"  This device : {socket.gethostname()}  ({ip}:{port})")
    say(f"  Saving to   : {folder}")
    say(f"  PIN         : {pin or 'none'}")


def cmd_receive(args):
    banner()
    r = Receiver(args.folder, args.pin, args.port)
    try:
        r.start()
    except OSError as e:
        sys.exit(f"Could not start receiving on port {args.port}: {e}\n(Is QuickDrop already running?)")
    show_ready(r.folder, args.pin, args.port)
    say("  Press Ctrl+C to stop.\n")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        say("\nStopped.")
    finally:
        r.stop()


def cmd_send(args):
    banner()
    files = collect_files(args.files)
    if not files:
        files = collect_files(prompt_paths())
    if not files:
        sys.exit("No files to send.")
    host = args.to or pick_receiver()
    if not host:
        sys.exit("No address given.")
    total = sum(f.stat().st_size for f in files)
    say(f"Sending {len(files)} file(s), {human(total)} to {host}:{args.port}")
    try:
        send_files(host, files, args.pin, args.port)
        say("Done - all files delivered.")
    except Exception as e:  # noqa: BLE001
        say(f"\nTransfer failed: {e}")
        if "PIN" in str(e):
            say("Tip: pass the receiver's PIN with --pin 1234")
        sys.exit(1)


def cmd_scan(args):
    banner()
    say("Looking for devices...")
    found = scan(3)
    if not found:
        say("None found. Make sure QuickDrop is running on the other device (same Wi-Fi).")
    for ip, name in found:
        say(f"  {name}  ({ip})")


def cmd_web(args):
    banner()
    w = WebServer(args.folder, args.send_folder, args.pin, args.port)
    try:
        w.start()
    except OSError as e:
        sys.exit(f"Could not start browser mode on port {args.port}: {e}")
    say("Browser mode is on. On your phone (same Wi-Fi) open:")
    for ip in local_ips():
        say(f"    http://{ip}:{args.port}")
    say(f"  Received files go to : {w.receive_folder}")
    say(f"  Files to download    : {w.send_folder}  (put files in this folder)")
    say(f"  PIN                  : {args.pin or 'none'}")
    say("  Press Ctrl+C to stop.\n")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        say("\nStopped.")
    finally:
        w.stop()


def prompt_paths():
    say("Enter file paths one per line (on Windows you can drag files into this window).")
    say("Press Enter on an empty line when done.")
    paths = []
    while True:
        line = clean_path(input("> "))
        if not line:
            return paths
        paths.append(line)


def menu(args):
    banner()
    say(f"This device: {socket.gethostname()}  ({local_ip()})\n")
    say("  1) Receive + send  (stay open, receive anytime, send when you want)")
    say("  2) Receive only")
    say("  3) Send files now")
    say("  4) Browser mode  (the phone needs no app, just a browser)")
    say("  q) Quit")
    choice = input("\nChoose [1]: ").strip().lower() or "1"
    folder = args.folder
    pin = input("PIN (optional, press Enter to skip): ").strip() if choice in "124" else ""
    if choice == "1":
        r = Receiver(folder, pin, PORT, show_progress=False)
        try:
            r.start()
        except OSError as e:
            sys.exit(f"Could not start receiving on port {PORT}: {e}\n(Is QuickDrop already running?)")
        show_ready(r.folder, pin)
        target = None
        while True:
            say("\nPaste or drag file paths to send them (one per line, blank line to send).")
            say("Type q to quit, or just leave this window open to receive.")
            paths = []
            while True:
                line = clean_path(input("> "))
                if line.lower() == "q":
                    r.stop()
                    return
                if not line:
                    break
                paths.append(line)
            files = collect_files(paths)
            if not files:
                continue
            target = target or pick_receiver()
            send_pin = input("PIN of the other device (Enter if none): ").strip()
            try:
                send_files(target, files, send_pin)
                say("Done - all files delivered.")
            except Exception as e:  # noqa: BLE001
                say(f"Transfer failed: {e}")
                target = None
    elif choice == "2":
        a = argparse.Namespace(folder=folder, pin=pin, port=PORT)
        cmd_receive(a)
    elif choice == "3":
        a = argparse.Namespace(files=[], to="", pin=input("PIN of the other device (Enter if none): ").strip(), port=PORT)
        cmd_send(a)
    elif choice == "4":
        a = argparse.Namespace(folder=folder, send_folder=str(Path(folder).parent / "QuickDrop-ToSend"),
                               pin=pin, port=WEB_PORT)
        cmd_web(a)


def main():
    ap = argparse.ArgumentParser(description="QuickDrop - send files between phone and PC over Wi-Fi")
    sub = ap.add_subparsers(dest="cmd")

    r = sub.add_parser("receive", help="receive files")
    r.add_argument("--pin", default="")
    r.add_argument("--folder", default=str(default_folder()))
    r.add_argument("--port", type=int, default=PORT)

    s = sub.add_parser("send", help="send files")
    s.add_argument("files", nargs="*")
    s.add_argument("--to", default="", help="IP of the other device (skips auto-discovery)")
    s.add_argument("--pin", default="")
    s.add_argument("--port", type=int, default=PORT)

    sub.add_parser("scan", help="list devices running QuickDrop")

    w = sub.add_parser("web", help="browser mode (phone needs no app)")
    w.add_argument("--pin", default="")
    w.add_argument("--folder", default=str(default_folder()), help="where uploads are saved")
    w.add_argument("--send-folder", dest="send_folder",
                   default=str(default_folder().parent / "QuickDrop-ToSend"),
                   help="files in this folder can be downloaded by the phone")
    w.add_argument("--port", type=int, default=WEB_PORT)

    ap.add_argument("--folder", default=str(default_folder()))
    args = ap.parse_args()

    try:
        if args.cmd == "receive":
            cmd_receive(args)
        elif args.cmd == "send":
            cmd_send(args)
        elif args.cmd == "scan":
            cmd_scan(args)
        elif args.cmd == "web":
            cmd_web(args)
        else:
            menu(args)
    except (KeyboardInterrupt, EOFError):
        say("\nBye.")
    except SystemExit as e:
        if e.code not in (None, 0):
            say(str(e.code))
        _pause_if_exe()
        raise
    _pause_if_exe()


def _pause_if_exe():
    """When double-clicked as an .exe, keep the window open so messages can be read."""
    if getattr(sys, "frozen", False):
        try:
            input("\nPress Enter to close...")
        except (EOFError, KeyboardInterrupt):
            pass


if __name__ == "__main__":
    main()
