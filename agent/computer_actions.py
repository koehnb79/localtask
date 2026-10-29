"""Fixed local computer actions. No arbitrary commands, elevation, or remote session IDs."""
import ctypes
import logging
import os
from pathlib import Path
import platform
import re
import secrets
import socket
import subprocess
import threading
import time
from dataclasses import dataclass

from fastapi import HTTPException
from pydantic import BaseModel, ConfigDict, Field
from typing import Literal

LOG = logging.getLogger("localtask")
Action = Literal["logout", "shutdown", "restart"]
LABELS = {"logout": "Log off", "shutdown": "Shut down", "restart": "Restart"}


class PrepareAction(BaseModel):
    model_config = ConfigDict(extra="forbid")
    action: Action


class ConfirmAction(BaseModel):
    model_config = ConfigDict(extra="forbid")
    confirmation: str = Field(pattern=r"^[A-Za-z0-9_-]{43}$", strict=True)


@dataclass(frozen=True)
class Plan:
    action: str
    argv: tuple
    target: str
    warning: str


def run_command(argv, timeout=5):
    return subprocess.run(list(argv), stdin=subprocess.DEVNULL, capture_output=True,
                          text=True, errors="replace", timeout=timeout, check=False, shell=False)


def executable(*paths):
    for path in paths:
        if Path(path).is_file() and os.access(path, os.X_OK):
            return str(path)
    raise HTTPException(409, "The required operating-system tool is not installed.")


def windows_session():
    session = ctypes.c_ulong()
    if not ctypes.windll.kernel32.ProcessIdToSessionId(os.getpid(), ctypes.byref(session)):
        raise HTTPException(409, "Could not identify the agent's Windows session.")
    if session.value == 0:
        raise HTTPException(409, "Log off is unavailable from a Windows service. Run the agent in your desktop session.")
    return str(session.value)


def linux_session(loginctl):
    # 'auto' also works for a systemd user service, which has no session of its own.
    result = run_command((loginctl, "show-session", "auto", "--no-pager",
                          "--property=Id", "--property=User", "--property=Type",
                          "--property=Class", "--property=Remote", "--property=Active"))
    props = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
    session = props.get("Id", "")
    if (result.returncode or not re.fullmatch(r"[A-Za-z0-9_]+", session)
            or props.get("User") != str(os.getuid())
            or props.get("Type") not in ("x11", "wayland")
            or props.get("Class") != "user" or props.get("Remote") != "no"
            or props.get("Active") != "yes"):
        raise HTTPException(409, "No active local desktop session belonging to the agent's user was found. SSH, text, and other users' sessions cannot be logged off.")
    return session


def build_plan(action):
    if action not in LABELS:
        raise HTTPException(422, "Unknown computer action.")
    system = platform.system()
    warning = "Save work first. Unsaved work may be lost. "
    if action == "logout":
        warning += "This signs out the agent's desktop session and may stop the agent until someone signs in again. "
    else:
        warning += "This affects the entire computer, including other signed-in users. The connection will end. "
        warning += ("LocalTask cannot turn it back on. " if action == "shutdown" else
                    "The agent may not return until someone signs in again. ")
    if system == "Linux":
        if action == "logout":
            tool = executable("/usr/bin/loginctl", "/bin/loginctl")
            session = linux_session(tool)
            warning += "Linux ends this session's processes without save prompts."
            return Plan(action, (tool, "--no-ask-password", "terminate-session", session),
                        "Your local desktop session " + session, warning)
        tool = executable("/usr/bin/systemctl", "/bin/systemctl")
        return Plan(action, (tool, "--no-ask-password", "--check-inhibitors=yes",
                             "poweroff" if action == "shutdown" else "reboot"),
                    "Entire computer", warning + "System policy or an application may block the request.")
    if system == "Darwin":
        # Never send desktop Apple events on behalf of a different foreground user.
        if os.stat("/dev/console").st_uid != os.getuid():
            raise HTTPException(409, "The agent's user must be signed in to the Mac's foreground desktop.")
        tool = executable("/usr/bin/osascript")
        verb = {"logout": "log out", "shutdown": "shut down", "restart": "restart"}[action]
        return Plan(action, (tool, "-e", 'tell application "System Events" to ' + verb),
                    "Your Mac desktop" if action == "logout" else "Entire computer",
                    warning + "macOS may show a local permission or save dialog, or an application may cancel the request.")
    if system == "Windows":
        # Resolve the OS tool from the Windows system directory, never PATH/current directory.
        buffer = ctypes.create_unicode_buffer(32768)
        length = ctypes.windll.kernel32.GetSystemDirectoryW(buffer, len(buffer))
        if not length or length >= len(buffer):
            raise HTTPException(409, "Could not locate the Windows system directory.")
        tool = executable(str(Path(buffer.value) / "shutdown.exe"))
        if action == "logout":
            session = windows_session()
            return Plan(action, (tool, "/l"), "Your Windows session " + session, warning)
        # A positive /t implicitly forces applications closed. Use 0 and never /f.
        return Plan(action, (tool, "/s" if action == "shutdown" else "/r", "/t", "0"),
                    "Entire computer", warning + "Windows permissions or an application may block the request.")
    raise HTTPException(409, "Computer actions are not supported on this operating system.")


class ComputerActions:
    def __init__(self, planner=build_plan, runner=run_command, clock=time.monotonic):
        self.planner, self.runner, self.clock = planner, runner, clock
        self.lock = threading.Lock()
        self.pending = {}
        self.running = False
        self.cooldown_until = 0

    def describe(self):
        actions = []
        for action, label in LABELS.items():
            try:
                plan = self.planner(action)
                actions.append(dict(action=action, label=label, available=True,
                                    target=plan.target, warning=plan.warning))
            except (HTTPException, OSError, subprocess.SubprocessError) as exc:
                reason = exc.detail if isinstance(exc, HTTPException) else "Could not inspect the local desktop session."
                actions.append(dict(action=action, label=label, available=False, reason=reason))
        return dict(computer=socket.gethostname(), actions=actions)

    def prepare(self, action):
        try:
            plan = self.planner(action)
        except (OSError, subprocess.SubprocessError):
            raise HTTPException(409, "Could not inspect the computer's session. No action was sent.")
        with self.lock:
            now = self.clock()
            self.pending = {k: v for k, v in self.pending.items() if v[0] > now}
            if self.running or now < self.cooldown_until:
                raise HTTPException(409, "A computer action was recently requested. Check the computer before trying again in one minute.")
            if len(self.pending) >= 32:
                raise HTTPException(429, "Too many pending confirmations. Wait one minute and try again.")
            ticket = secrets.token_urlsafe(32)
            self.pending[ticket] = (now + 60, plan)
        return dict(confirmation=ticket, expires_in=60, computer=socket.gethostname(),
                    action=action, label=LABELS[action], target=plan.target, warning=plan.warning)

    def execute(self, confirmation):
        with self.lock:
            item = self.pending.pop(confirmation, None)
            if not item or item[0] <= self.clock():
                raise HTTPException(409, "Confirmation expired or was already used. No new action was sent.")
            if self.running or self.clock() < self.cooldown_until:
                raise HTTPException(409, "A computer action is already pending. No new action was sent.")
            self.running = True
        plan = item[1]
        try:
            # Re-resolve the target immediately before dispatch. Never switch sessions silently.
            if self.planner(plan.action) != plan:
                raise HTTPException(409, "The desktop session changed. No action was sent; open Computer actions again.")
            with self.lock:
                self.pending.clear()
                self.cooldown_until = self.clock() + 60
            LOG.info("computer_action=%s requested", plan.action)
            try:
                result = self.runner(plan.argv)
            except subprocess.TimeoutExpired:
                raise HTTPException(504, "The operating system did not reply in time. The action may already be underway. Check the computer; do not immediately repeat it.")
            except OSError:
                raise HTTPException(409, "Could not start the operating-system tool. Check the agent installation and permissions.")
            if result.returncode != 0:
                system = platform.system()
                hint = {"Linux": "Check logind/polkit permission and shutdown inhibitors; systemctl must support --check-inhibitors (systemd 248+).",
                        "Darwin": "Check the Mac for an Automation permission or save dialog. Allow the agent's launcher to control System Events if prompted.",
                        "Windows": "The agent's account needs the Shut down the system user right for power actions."}.get(system, "Check local permissions.")
                LOG.warning("computer_action=%s tool_exit=%s", plan.action, result.returncode)
                raise HTTPException(409, "The operating system rejected or cancelled the request. " + hint)
            return dict(status="requested", action=plan.action,
                        message="Request sent to the operating system. This is not confirmation that it completed. Check the computer for any local prompts.")
        except (OSError, subprocess.SubprocessError):
            raise HTTPException(409, "Could not verify the desktop session. No action was sent.")
        finally:
            with self.lock:
                self.running = False
