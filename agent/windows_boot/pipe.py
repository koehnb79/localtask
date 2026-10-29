"""Local-only authenticated named-pipe IPC, bounded overlapped I/O, JSON only."""
import secrets
import threading
import time

import pywintypes
import win32api
import win32con
import win32event
import win32file
import win32pipe
import win32security
from fastapi import HTTPException
from .policy import (PIPE_NAME, NETWORK_SERVICE, service_sid, encode_message, decode_message,
                     MAX_REQUEST, MAX_RESPONSE, authorized_service_groups)

# Individual rights exclude FILE_CREATE_PIPE_INSTANCE (0x4), unlike GENERIC_WRITE.
CLIENT_ACCESS = 0x0012019B


def _finish(handle, overlapped, stopped, timeout):
    handles = [overlapped.hEvent] if stopped is None else [stopped, overlapped.hEvent]
    result = win32event.WaitForMultipleObjects(handles, False, timeout)
    if result != win32event.WAIT_OBJECT_0 + len(handles) - 1:
        win32file.CancelIoEx(handle, overlapped)
        try:
            win32file.GetOverlappedResult(handle, overlapped, True)
        except pywintypes.error:
            pass
        raise TimeoutError('IPC stopped or timed out')
    return win32file.GetOverlappedResult(handle, overlapped, False)


def transfer(handle, stopped, data=None, limit=MAX_RESPONSE):
    ov = pywintypes.OVERLAPPED()
    ov.hEvent = win32event.CreateEvent(None, True, False, None)
    buffer = win32file.AllocateReadBuffer(limit) if data is None else data
    try:
        if data is None:
            win32file.ReadFile(handle, buffer, ov)
        else:
            win32file.WriteFile(handle, buffer, ov)
        count = _finish(handle, ov, stopped, 15000)
        return bytes(buffer[:count]) if data is None else None
    finally:
        ov.hEvent.Close()


def _authorize_client(handle):
    win32pipe.ImpersonateNamedPipeClient(handle)
    try:
        with win32security.OpenThreadToken(win32api.GetCurrentThread(), win32con.TOKEN_QUERY, True) as token:
            groups = win32security.GetTokenInformation(token, win32security.TokenGroups)
            if not authorized_service_groups([(win32security.ConvertSidToStringSid(sid), attrs) for sid, attrs in groups]):
                raise PermissionError('Caller is not the LocalTask network service')
    finally:
        win32security.RevertToSelf()


class PipeServer:
    def __init__(self, key, stopped, dispatch):
        self.key, self.stopped, self.dispatch = key, stopped, dispatch

    def run(self):
        sa = pywintypes.SECURITY_ATTRIBUTES()
        sa.SECURITY_DESCRIPTOR = win32security.ConvertStringSecurityDescriptorToSecurityDescriptor(
            'D:P(A;;GA;;;SY)(A;;0x0012019b;;;' + service_sid(NETWORK_SERVICE) + ')', 1)
        # Reject remote SMB clients and refuse to join a pre-existing/squatted pipe.
        handle = win32pipe.CreateNamedPipe(PIPE_NAME, 0x3 | 0x40000000 | 0x00080000,
            0x4 | 0x2 | 0x8, 1, 65536, 65536, 5000, sa)
        seen = {}
        try:
            while win32event.WaitForSingleObject(self.stopped, 0) != win32event.WAIT_OBJECT_0:
                ov = pywintypes.OVERLAPPED(); ov.hEvent = win32event.CreateEvent(None, True, False, None)
                try:
                    connected = False
                    try:
                        connected = win32pipe.ConnectNamedPipe(handle, ov) == 535
                    except pywintypes.error as exc:
                        if exc.winerror == 535:  # ERROR_PIPE_CONNECTED
                            connected = True
                        elif exc.winerror != 997:
                            raise
                    if not connected:
                        _finish(handle, ov, self.stopped, win32event.INFINITE)
                    raw = transfer(handle, self.stopped, limit=MAX_REQUEST)
                    _authorize_client(handle)
                    nonce, request = decode_message(self.key, raw, MAX_REQUEST)
                    now = time.monotonic()
                    seen = {k: v for k, v in seen.items() if now - v < 120}
                    if nonce in seen or len(seen) >= 1024:
                        raise ValueError('Repeated request or rate limit exceeded')
                    seen[nonce] = now
                    response = self.dispatch(request)
                    reply = encode_message(self.key, nonce, response)
                    if len(reply) > MAX_RESPONSE:
                        reply = encode_message(self.key, nonce, {'status': 503, 'detail': 'Process list is too large'})
                    transfer(handle, self.stopped, data=reply)
                    # Wait until the client has consumed the reply before disconnecting.
                    transfer(handle, self.stopped, limit=16)
                except (pywintypes.error, TimeoutError, ValueError, PermissionError):
                    # Do not log payloads: they may contain a confirmation token.
                    pass
                finally:
                    ov.hEvent.Close()
                    try:
                        win32pipe.DisconnectNamedPipe(handle)
                    except pywintypes.error:
                        pass
        finally:
            handle.Close()


class PipeClient:
    def __init__(self, key):
        self.key = key
        self.lock = threading.Lock()

    def call(self, op, body=None):
        nonce = secrets.token_hex(32)
        raw = encode_message(self.key, nonce, {'op': op, 'body': body or {}})
        if len(raw) > MAX_REQUEST:
            raise HTTPException(422, 'Request too large')
        try:
            with self.lock:
                win32pipe.WaitNamedPipe(PIPE_NAME, 5000)
                with win32file.CreateFile(PIPE_NAME, CLIENT_ACCESS, 0, None, win32con.OPEN_EXISTING,
                                         win32con.FILE_FLAG_OVERLAPPED, None) as handle:
                    win32pipe.SetNamedPipeHandleState(handle, win32pipe.PIPE_READMODE_MESSAGE, None, None)
                    transfer(handle, None, data=raw)
                    reply = transfer(handle, None, limit=MAX_RESPONSE)
                    transfer(handle, None, data=b'ok')
                _, result = decode_message(self.key, reply, MAX_RESPONSE, nonce)
        except (pywintypes.error, OSError, ValueError) as exc:
            # A broken reply after dispatch is not proof the command failed. Never retry.
            raise HTTPException(504, 'The local helper did not confirm the result. An action may already be underway; check the computer before repeating it.') from exc
        if not isinstance(result, dict) or type(result.get('status')) is not int:
            raise HTTPException(502, 'Invalid reply from local helper')
        if result['status'] != 200:
            raise HTTPException(result['status'], result.get('detail', 'Local helper rejected the request'))
        return result['result']

    def snapshot(self): return self.call('snapshot')
    def command(self, cmd): return self.call('process', cmd.model_dump())
    def describe(self): return self.call('describe')
    def prepare(self, action): return self.call('prepare', {'action': action})
    def execute(self, confirmation): return self.call('confirm', {'confirmation': confirmation})
