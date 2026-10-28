"""LocalTask agent: run as the signed-in user, never as root/LocalSystem."""
import argparse
import ipaddress
import json
import logging
import os
from pathlib import Path
import platform
import secrets
import socket
import threading
import time
from datetime import datetime, timedelta, timezone
from typing import Literal

import psutil
import uvicorn
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field

LOG = logging.getLogger("localtask")


def initialize(directory: Path):
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    if any(directory.iterdir()):
        raise ValueError("Use a new empty config directory; refusing to overwrite credentials")
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "LocalTask")])
    now = datetime.now(timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject)
            .public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - timedelta(minutes=5)).not_valid_after(now + timedelta(days=3650))
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .sign(key, hashes.SHA256()))
    values = {
        "key.pem": key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                     serialization.NoEncryption()),
        "cert.pem": cert.public_bytes(serialization.Encoding.PEM),
        "token": secrets.token_urlsafe(32).encode(),
    }
    for name, data in values.items():
        fd = os.open(directory / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as f:
            f.write(data)


def credentials(directory: Path):
    cert = x509.load_pem_x509_certificate((directory / "cert.pem").read_bytes())
    return {"fingerprint": cert.fingerprint(hashes.SHA256()).hex(),
            "token": (directory / "token").read_text().strip()}


class Command(BaseModel):
    model_config = ConfigDict(extra="forbid")
    pid: int = Field(gt=1, strict=True)
    created: float = Field(gt=0, allow_inf_nan=False)
    action: Literal["terminate", "kill"]


class Processes:
    def __init__(self):
        self.owner = psutil.Process().username()
        self.lock = threading.Lock()
        self.previous = {}
        self.protected = {os.getpid(), 0, 1, 4}
        self.protected.update(p.pid for p in psutil.Process().parents())

    def snapshot(self):
        # CPU is computed from deltas, separately from psutil's global process cache.
        with self.lock:
            now = time.monotonic()
            previous, current, result = self.previous, {}, []
            for proc in psutil.process_iter():
                try:
                    with proc.oneshot():
                        created = proc.create_time()
                        owner = proc.username()
                        cpu = proc.cpu_times()
                        total = cpu.user + cpu.system
                        identity = (proc.pid, created)
                        old = previous.get(identity)
                        percent = max(0, (total - old[1]) / (now - old[0]) * 100) if old else None
                        current[identity] = (now, total)
                        result.append(dict(pid=proc.pid, created=created, name=proc.name(),
                                           owner=owner, cpu=percent, memory=proc.memory_info().rss,
                                           manageable=owner == self.owner and proc.pid not in self.protected))
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    continue
            self.previous = current
            return sorted(result, key=lambda p: p["memory"], reverse=True)

    def command(self, cmd: Command):
        if cmd.pid in self.protected:
            raise HTTPException(403, "Agent, ancestors and core processes are protected")
        try:
            # New Process instance captures identity; psutil rechecks before signalling.
            proc = psutil.Process(cmd.pid)
            if proc.create_time() != cmd.created:
                raise HTTPException(409, "Process changed; refresh before trying again")
            if proc.username() != self.owner:
                raise HTTPException(403, "Only this user's processes may be managed")
            if cmd.action == "terminate" and platform.system() == "Windows":
                raise HTTPException(400, "Windows requires the explicit force-stop action")
            getattr(proc, cmd.action)()
            LOG.info("action=%s pid=%s created=%s accepted", cmd.action, cmd.pid, cmd.created)
            return {"status": "signal_sent", "pid": cmd.pid}
        except psutil.NoSuchProcess:
            raise HTTPException(404, "Process has already exited")
        except psutil.AccessDenied:
            raise HTTPException(403, "Operating system denied permission")


def make_app(token: str, processes=None):
    if len(token) < 32:
        raise ValueError("A strong token is required")
    processes = processes or Processes()
    def authorize(authorization: str = Header(default="")):
        if not secrets.compare_digest(authorization.encode(), ("Bearer " + token).encode()):
            raise HTTPException(401, "Invalid access key")
    app = FastAPI(title="LocalTask", docs_url=None, redoc_url=None, openapi_url=None,
                  dependencies=[Depends(authorize)])

    @app.get("/v1/processes")
    def listing():
        return {"computer": socket.gethostname(), "os": platform.system(),
                "sampled_at": time.time(), "processes": processes.snapshot()}

    @app.post("/v1/commands")
    def command(cmd: Command):
        return processes.command(cmd)
    return app


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["init", "pair", "serve", "revoke"])
    parser.add_argument("--config", type=Path, default=Path.home() / ".localtask")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    if args.action == "init":
        initialize(args.config)
        print("Initialized. Run pair locally to display connection credentials.")
    elif args.action == "pair":
        print(json.dumps(credentials(args.config), indent=2))
    elif args.action == "revoke":
        path = args.config / "token"
        with path.open("w") as f:
            f.write(secrets.token_urlsafe(32))
        print("Access key replaced. RESTART the agent to revoke existing access.")
    else:
        address = ipaddress.ip_address(args.host)
        local = (ipaddress.ip_network("10.0.0.0/8"), ipaddress.ip_network("172.16.0.0/12"),
                 ipaddress.ip_network("192.168.0.0/16"))
        if address.version != 4 or not (address.is_loopback or any(address in n for n in local)):
            parser.error("Bind to a specific private LAN IPv4 address or loopback")
        if hasattr(os, "geteuid") and os.geteuid() == 0:
            parser.error("Run the agent as your normal desktop user, not root")
        if platform.system() == "Windows":
            import ctypes
            if ctypes.windll.shell32.IsUserAnAdmin():
                parser.error("Run as a standard desktop user, not Administrator")
        token = credentials(args.config)["token"]
        logging.basicConfig(level=logging.INFO)
        uvicorn.run(make_app(token), host=args.host, port=args.port,
                    ssl_keyfile=str(args.config / "key.pem"),
                    ssl_certfile=str(args.config / "cert.pem"),
                    access_log=False, limit_concurrency=16, timeout_keep_alive=5)

if __name__ == "__main__":
    main()
