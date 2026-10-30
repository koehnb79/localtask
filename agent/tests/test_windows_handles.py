"""Portable regression tests for real adapters with non-context-manager handles.

Windows APIs are replaced at the boundary; no process/power action is executed.
Run directly with unittest as well as through the full pytest suite.
"""
import ctypes
import importlib.util
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1] / 'windows_boot'


class Handle:
    # PyHANDLE supplies close/Close, but does not implement __enter__/__exit__.
    def __init__(self):
        self.closed = 0

    def close(self):
        self.closed += 1

    Close = close

    def __int__(self):
        return 123


class WindowsError(Exception):
    pass


class HttpError(Exception):
    def __init__(self, status_code, detail):
        self.status_code = status_code
        super().__init__(detail)


class HandleRegressionTests(unittest.TestCase):
    def setUp(self):
        names = ['win32api', 'win32con', 'win32process', 'win32security',
                 'win32ts', 'pywintypes', 'win32event', 'win32file', 'win32pipe']
        self.modules = {name: Mock(name=name) for name in names}
        # Unlike an unrestricted Mock, this rejects APIs owned by another module.
        self.modules['win32pipe'] = Mock(spec=[
            'CreateNamedPipe', 'ConnectNamedPipe', 'DisconnectNamedPipe',
            'WaitNamedPipe', 'SetNamedPipeHandleState', 'PIPE_READMODE_MESSAGE'])
        self.modules['win32file'] = Mock(spec=[
            'CreateFile', 'ReadFile', 'WriteFile', 'AllocateReadBuffer',
            'CancelIo', 'GetOverlappedResult'])
        self.modules['win32event'].WAIT_OBJECT_0 = 0
        for name in ('win32api', 'pywintypes'):
            self.modules[name].error = WindowsError
        for name, value in dict(TOKEN_QUERY=8, TOKEN_ADJUST_PRIVILEGES=32,
                                PROCESS_TERMINATE=1, SE_PRIVILEGE_ENABLED=2,
                                OPEN_EXISTING=3, FILE_FLAG_OVERLAPPED=0x40000000).items():
            setattr(self.modules['win32con'], name, value)
        self.security = self.modules['win32security']
        self.api = self.modules['win32api']
        self.token = Handle()
        self.process = Handle()
        self.api.OpenProcess.return_value = self.process
        self.security.OpenProcessToken.return_value = self.token
        self.security.OpenThreadToken.return_value = self.token
        self.security.GetTokenInformation.return_value = ('sid', 0)
        self.security.ConvertSidToStringSid.return_value = 'S-1-5-21-test'
        package = types.ModuleType('_localtask_handle_tests')
        package.__path__ = [str(ROOT)]
        policy = Mock()
        policy.MAX_REQUEST = 4096
        policy.MAX_RESPONSE = 8388608
        policy.PIPE_NAME = 'test-pipe'
        policy.encode_message.return_value = b'request'
        policy.decode_message.return_value = ('nonce', {'status': 200, 'result': {'ok': True}})
        policy.authorized_service_groups.return_value = True
        self.policy = policy
        self.modules.update({'_localtask_handle_tests': package,
                             '_localtask_handle_tests.policy': policy,
                             'fastapi': types.SimpleNamespace(HTTPException=HttpError)})
        self.patcher = patch.dict(sys.modules, self.modules)
        self.patcher.start()
        self.addCleanup(self.patcher.stop)
        self.kernel = Mock()
        self.kernel.GetProcessTimes.return_value = True
        self.kernel.IsProcessCritical.return_value = True
        with patch.object(ctypes, 'WinDLL', return_value=self.kernel, create=True):
            self.native = self.load('native')
        self.pipe = self.load('pipe')

    def load(self, name):
        spec = importlib.util.spec_from_file_location('_localtask_handle_tests.' + name, ROOT / (name + '.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_own_sid_closes_token(self):
        self.assertEqual(self.native.own_sid(), 'S-1-5-21-test')
        self.assertEqual(self.token.closed, 1)

    def test_own_sid_closes_token_on_query_failure(self):
        self.security.GetTokenInformation.side_effect = WindowsError()
        with self.assertRaises(WindowsError):
            self.native.own_sid()
        self.assertEqual(self.token.closed, 1)

    def test_process_inspection_closes_token_and_process(self):
        with self.native.Native().process(99) as inspected:
            self.assertEqual(inspected.sid, 'S-1-5-21-test')
            self.assertEqual(self.token.closed, 1)
            self.assertEqual(self.process.closed, 0)
        self.assertEqual(self.process.closed, 1)

    def test_process_constructor_failure_closes_both_handles(self):
        self.security.GetTokenInformation.side_effect = WindowsError()
        with self.assertRaises(OSError):
            self.native.Native().process(99)
        self.assertEqual((self.token.closed, self.process.closed), (1, 1))

    def test_later_process_inspection_failure_closes_process(self):
        self.kernel.GetProcessTimes.side_effect = RuntimeError('inspection failed')
        with self.assertRaises(RuntimeError):
            self.native.Native().process(99)
        self.assertEqual((self.token.closed, self.process.closed), (1, 1))

    def test_session_inspection_closes_token(self):
        ts = self.modules['win32ts']
        ts.WTSEnumerateSessions.return_value = [{'SessionId': 1, 'State': ts.WTSActive}]
        ts.WTSQuerySessionInformation.return_value = 'user'
        ts.WTSQueryUserToken.return_value = self.token
        self.security.GetTokenInformation.side_effect = [('sid', 0), {'AuthenticationId': 123}]
        self.assertEqual(self.native.Native().sessions()[0]['login'], '123')
        self.assertEqual(self.token.closed, 1)

    def test_power_restores_privileges_and_closes_token(self):
        old = [('privilege', 0)]
        self.security.AdjustTokenPrivileges.return_value = old
        self.native.Native().power(False)
        self.security.AdjustTokenPrivileges.assert_called_with(self.token, False, old)
        self.assertEqual(self.token.closed, 1)

    def test_power_failure_restores_privileges_and_closes_token(self):
        self.api.InitiateSystemShutdown.side_effect = WindowsError()
        self.security.AdjustTokenPrivileges.return_value = []
        with self.assertRaises(HttpError):
            self.native.Native().power(False)
        self.security.AdjustTokenPrivileges.assert_called_with(self.token, False, [])
        self.assertEqual(self.token.closed, 1)

    def test_completed_io_does_not_cancel(self):
        handle, ov = Handle(), types.SimpleNamespace(hEvent=Handle())
        self.modules['win32event'].WaitForMultipleObjects.return_value = 0
        api = self.modules['win32file']
        api.GetOverlappedResult.return_value = 12
        self.assertEqual(self.pipe._finish(handle, ov, None, 100), 12)
        api.CancelIo.assert_not_called()
        api.GetOverlappedResult.assert_called_once_with(handle, ov, False)

    def test_timeout_and_stop_cancel_then_drain_pending_io(self):
        for stopped, result in [(None, 258), (Handle(), 0)]:
            with self.subTest(stopped=stopped):
                handle, ov = Handle(), types.SimpleNamespace(hEvent=Handle())
                self.modules['win32event'].WaitForMultipleObjects.return_value = result
                api = self.modules['win32file']
                api.reset_mock()
                calls = []
                api.CancelIo.side_effect = lambda h: calls.append(('cancel', h))
                def drained(h, o, wait):
                    calls.append(('drain', h, o, wait))
                    raise WindowsError('operation aborted')
                api.GetOverlappedResult.side_effect = drained
                with self.assertRaises(TimeoutError):
                    self.pipe._finish(handle, ov, stopped, 100)
                self.assertEqual(calls, [('cancel', handle), ('drain', handle, ov, True)])

    def test_cancellation_completion_race_still_reports_timeout(self):
        handle, ov = Handle(), types.SimpleNamespace(hEvent=Handle())
        self.modules['win32event'].WaitForMultipleObjects.return_value = 258
        self.modules['win32file'].GetOverlappedResult.return_value = 12
        with self.assertRaises(TimeoutError):
            self.pipe._finish(handle, ov, None, 100)
        self.modules['win32file'].CancelIo.assert_called_once_with(handle)
        self.modules['win32file'].GetOverlappedResult.assert_called_once_with(handle, ov, True)

    def test_transfer_timeout_closes_event_after_draining(self):
        event = Handle()
        self.modules['win32event'].CreateEvent.return_value = event
        self.modules['win32event'].WaitForMultipleObjects.return_value = 258
        api = self.modules['win32file']
        def drain(*args):
            self.assertEqual(event.closed, 0)
            raise WindowsError('operation aborted')
        api.GetOverlappedResult.side_effect = drain
        with self.assertRaises(TimeoutError):
            self.pipe.transfer(Handle(), None, data=b'check')
        self.assertEqual(event.closed, 1)

    def test_pipe_authorization_closes_token_and_reverts(self):
        self.security.GetTokenInformation.return_value = [('service-sid', 4)]
        handle = Handle()
        self.pipe._authorize_client(handle)
        self.security.ImpersonateNamedPipeClient.assert_called_once_with(handle)
        self.assertEqual(self.token.closed, 1)
        self.security.RevertToSelf.assert_called_once()

    def test_impersonation_failure_does_not_open_token(self):
        self.security.ImpersonateNamedPipeClient.side_effect = WindowsError()
        with self.assertRaises(WindowsError):
            self.pipe._authorize_client(Handle())
        self.security.OpenThreadToken.assert_not_called()
        self.security.RevertToSelf.assert_not_called()

    def test_token_open_failure_still_reverts_impersonation(self):
        self.security.OpenThreadToken.side_effect = WindowsError()
        with self.assertRaises(WindowsError):
            self.pipe._authorize_client(Handle())
        self.security.RevertToSelf.assert_called_once()

    def test_packaging_api_check_accepts_expected_modules(self):
        self.pipe.check_api()

    def test_packaging_api_check_detects_missing_security_api(self):
        self.security.__name__ = 'win32security'
        self.security.ImpersonateNamedPipeClient = None
        with self.assertRaisesRegex(RuntimeError, 'win32security.ImpersonateNamedPipeClient'):
            self.pipe.check_api()

    def test_denied_pipe_caller_closes_token_and_reverts(self):
        self.security.GetTokenInformation.return_value = [('other-sid', 4)]
        self.policy.authorized_service_groups.return_value = False
        with self.assertRaises(PermissionError):
            self.pipe._authorize_client(Handle())
        self.assertEqual(self.token.closed, 1)
        self.security.RevertToSelf.assert_called_once()

    def test_pipe_client_closes_file_handle(self):
        self.modules['win32file'].CreateFile.return_value = self.process
        with patch.object(self.pipe, 'transfer', return_value=b'reply'):
            self.assertEqual(self.pipe.PipeClient(b'key').call('health'), {'ok': True})
        self.assertEqual(self.process.closed, 1)

    def test_pipe_transfer_failure_closes_file_handle_without_retry(self):
        self.modules['win32file'].CreateFile.return_value = self.process
        with patch.object(self.pipe, 'transfer', side_effect=OSError('lost reply')) as transfer:
            with self.assertRaises(HttpError) as caught:
                self.pipe.PipeClient(b'key').call('health')
        self.assertEqual(caught.exception.status_code, 504)
        self.assertEqual(self.process.closed, 1)
        transfer.assert_called_once()


if __name__ == '__main__':
    unittest.main()
