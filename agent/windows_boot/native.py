"""Windows calls used only by the privileged helper. Never accepts shell commands."""
import ctypes
from ctypes import wintypes
from contextlib import closing

import win32api
import win32con
import win32process
import win32security
import win32ts
from fastapi import HTTPException

_kernel = ctypes.WinDLL('kernel32', use_last_error=True)
_kernel.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
_kernel.GetProcessTimes.restype = wintypes.BOOL
_kernel.IsProcessCritical.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.BOOL)]
_kernel.IsProcessCritical.restype = wintypes.BOOL


def token_sid(token):
    return win32security.ConvertSidToStringSid(win32security.GetTokenInformation(token, win32security.TokenUser)[0])


def own_sid():
    with closing(win32security.OpenProcessToken(win32api.GetCurrentProcess(), win32con.TOKEN_QUERY)) as token:
        return token_sid(token)


class ProcessHandle:
    def __init__(self, pid, terminate):
        self.handle = win32api.OpenProcess(0x1000 | (win32con.PROCESS_TERMINATE if terminate else 0), False, pid)
        try:
            with closing(win32security.OpenProcessToken(self.handle, win32con.TOKEN_QUERY)) as token:
                self.sid = token_sid(token)
            values = [wintypes.FILETIME() for _ in range(4)]
            if not _kernel.GetProcessTimes(int(self.handle), *[ctypes.byref(v) for v in values]):
                raise ctypes.WinError(ctypes.get_last_error())
            created = (values[0].dwHighDateTime << 32) | values[0].dwLowDateTime
            self.created = created / 10_000_000 - 11_644_473_600
            critical = wintypes.BOOL()
            # Unknown critical state must never permit termination.
            self.critical = not _kernel.IsProcessCritical(int(self.handle), ctypes.byref(critical)) or bool(critical.value)
        except BaseException:
            self.handle.Close()
            raise

    def __enter__(self):
        return self

    def __exit__(self, *unused):
        self.handle.Close()

    def terminate(self):
        try:
            win32api.TerminateProcess(self.handle, 1)
        except win32api.error as exc:
            raise OSError('Windows denied termination') from exc


class Native:
    def process(self, pid, terminate=False):
        try:
            return ProcessHandle(pid, terminate)
        except win32api.error as exc:
            raise OSError('Windows denied process access') from exc

    def sessions(self):
        result = []
        for entry in win32ts.WTSEnumerateSessions(None, 1, 0):
            session = entry['SessionId']
            if session == 0 or entry['State'] not in (win32ts.WTSActive, win32ts.WTSDisconnected):
                continue
            try:
                name = win32ts.WTSQuerySessionInformation(None, session, win32ts.WTSUserName)
                if not name:
                    continue
                with closing(win32ts.WTSQueryUserToken(session)) as token:
                    sid = token_sid(token)
                    # AuthenticationId changes when a session ID is reused by a new logon.
                    login = str(win32security.GetTokenInformation(token, win32security.TokenStatistics)['AuthenticationId'])
                result.append({'id': session, 'sid': sid, 'login': login})
            except win32api.error as exc:
                raise HTTPException(409, 'Windows could not verify the signed-in sessions; no logout is available') from exc
        return result

    def logoff(self, session):
        try:
            win32ts.WTSLogoffSession(None, session, False)
        except win32api.error as exc:
            raise HTTPException(409, 'Windows rejected the logout request') from exc

    def power(self, restart):
        try:
            with closing(win32security.OpenProcessToken(win32api.GetCurrentProcess(), win32con.TOKEN_ADJUST_PRIVILEGES | win32con.TOKEN_QUERY)) as token:
                privilege = win32security.LookupPrivilegeValue(None, 'SeShutdownPrivilege')
                old = win32security.AdjustTokenPrivileges(token, False, [(privilege, win32con.SE_PRIVILEGE_ENABLED)])
                try:
                    # Fixed local target. Do not force applications with unsaved work closed.
                    win32api.InitiateSystemShutdown(None, 'Requested through LocalTask', 0, False, restart)
                finally:
                    win32security.AdjustTokenPrivileges(token, False, old)
        except win32api.error as exc:
            raise HTTPException(409, 'Windows rejected the shutdown/restart request; check local shutdown policy') from exc
