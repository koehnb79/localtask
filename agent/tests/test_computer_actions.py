"""All destructive commands are fakes: these tests never log out or power off the host."""
import sys
import threading
import subprocess
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import computer_actions as ca
from agent import make_app

TOKEN = "x" * 43
AUTH = {"Authorization": "Bearer " + TOKEN}


def plan(action):
    return ca.Plan(action, ("fake-tool", action), "test session", "Save your work.")


@pytest.fixture
def harness():
    runner = Mock(return_value=SimpleNamespace(returncode=0))
    clock = Mock(return_value=100.0)
    planner = Mock(side_effect=plan)
    actions = ca.ComputerActions(planner=planner, runner=runner, clock=clock)
    processes = Mock(); processes.snapshot.return_value = []
    return SimpleNamespace(actions=actions, runner=runner, clock=clock, planner=planner,
                           client=TestClient(make_app(TOKEN, processes, actions)))


def ticket(h, action="shutdown"):
    response = h.client.post('/v1/computer-actions/prepare', headers=AUTH, json={"action": action})
    assert response.status_code == 200
    return response.json()['confirmation']


def confirm(h, value):
    return h.client.post('/v1/computer-actions/confirm', headers=AUTH, json={"confirmation": value})


@pytest.mark.parametrize('method,path,body', [
    ('get', '/v1/computer-actions', None),
    ('post', '/v1/computer-actions/prepare', {'action': 'shutdown'}),
    ('post', '/v1/computer-actions/confirm', {'confirmation': 'x' * 43}),
])
def test_every_action_route_requires_auth(harness, method, path, body):
    kwargs = {} if body is None else {'json': body}
    for headers in ({}, {'Authorization': 'Bearer wrong'}):
        assert harness.client.request(method, path, headers=headers, **kwargs).status_code == 401
    harness.runner.assert_not_called()
    harness.planner.assert_not_called()


def test_read_and_prepare_are_non_destructive(harness):
    result = harness.client.get('/v1/computer-actions', headers=AUTH)
    assert result.status_code == 200
    assert len(result.json()['actions']) == 3
    ticket(harness)
    harness.runner.assert_not_called()


@pytest.mark.parametrize('action', ['logout', 'shutdown', 'restart'])
def test_single_use_confirmation_and_cooldown(harness, action):
    value = ticket(harness, action)
    other = ticket(harness, 'restart')
    assert confirm(harness, value).json()['status'] == 'requested'
    assert confirm(harness, value).status_code == 409
    assert confirm(harness, other).status_code == 409
    assert harness.client.post('/v1/computer-actions/prepare', headers=AUTH,
                               json={'action': action}).status_code == 409
    harness.runner.assert_called_once_with(('fake-tool', action))


def test_expired_and_unknown_confirmation_never_execute(harness):
    value = ticket(harness)
    harness.clock.return_value = 160.0
    assert confirm(harness, value).status_code == 409
    assert confirm(harness, 'z' * 43).status_code == 409
    harness.runner.assert_not_called()


@pytest.mark.parametrize('body', [{'action': 'shell'}, {'action': 'shutdown', 'command': 'anything'},
                                  {'action': 'logout', 'session': 'someone-else'}, {'action': 1}])
def test_no_arbitrary_commands_or_client_selected_sessions(harness, body):
    assert harness.client.post('/v1/computer-actions/prepare', headers=AUTH, json=body).status_code == 422
    harness.runner.assert_not_called()


def test_action_cannot_be_changed_at_confirmation(harness):
    value = ticket(harness, 'logout')
    assert harness.client.post('/v1/computer-actions/confirm', headers=AUTH,
                               json={'confirmation': value, 'action': 'shutdown'}).status_code == 422
    harness.runner.assert_not_called()


def test_session_change_requires_new_confirmation(harness):
    value = ticket(harness, 'logout')
    harness.planner.side_effect = lambda action: ca.Plan(action, ('fake-tool', 'other-session'), 'other', 'warning')
    assert confirm(harness, value).status_code == 409
    harness.runner.assert_not_called()


def test_permission_denial_is_reported_and_not_replayed(harness):
    value = ticket(harness)
    harness.runner.return_value.returncode = 1
    result = confirm(harness, value)
    assert result.status_code == 409
    assert 'rejected or cancelled' in result.json()['detail']
    assert confirm(harness, value).status_code == 409
    harness.runner.assert_called_once()


def test_timeout_has_unknown_outcome_and_no_retry(harness):
    value = ticket(harness)
    harness.runner.side_effect = subprocess.TimeoutExpired('fake-tool', 5)
    response = confirm(harness, value)
    assert response.status_code == 504
    assert 'may already be underway' in response.json()['detail']
    assert confirm(harness, value).status_code == 409
    harness.runner.assert_called_once()


def test_concurrent_confirmations_dispatch_once(harness):
    first, second = ticket(harness), ticket(harness)
    entered, release = threading.Event(), threading.Event()
    def run(argv):
        entered.set()
        assert release.wait(3)
        return SimpleNamespace(returncode=0)
    harness.runner.side_effect = run
    thread = threading.Thread(target=lambda: harness.actions.execute(first))
    thread.start()
    try:
        assert entered.wait(3)
        with pytest.raises(HTTPException):
            harness.actions.execute(second)
    finally:
        release.set(); thread.join(3)
    harness.runner.assert_called_once()


def test_pending_confirmations_are_bounded(harness):
    for _ in range(32):
        ticket(harness)
    assert harness.client.post('/v1/computer-actions/prepare', headers=AUTH,
                               json={'action': 'shutdown'}).status_code == 429
    harness.clock.return_value = 161
    ticket(harness)
    harness.runner.assert_not_called()


@pytest.fixture
def linux(monkeypatch):
    monkeypatch.setattr(ca.platform, 'system', lambda: 'Linux')
    monkeypatch.setattr(ca.os, 'getuid', lambda: 1000)
    monkeypatch.setattr(ca, 'executable', lambda *paths: paths[0])
    output = SimpleNamespace(returncode=0, stdout='Id=7\nUser=1000\nType=wayland\nClass=user\nRemote=no\nActive=yes\n')
    runner = Mock(return_value=output)
    monkeypatch.setattr(ca, 'run_command', runner)
    return output


def test_linux_logout_uses_verified_session(linux):
    result = ca.build_plan('logout')
    assert result.argv == ('/usr/bin/loginctl', '--no-ask-password', 'terminate-session', '7')
    assert 'without save prompts' in result.warning


@pytest.mark.parametrize('old,new', [('User=1000', 'User=2000'), ('Type=wayland', 'Type=tty'),
    ('Remote=no', 'Remote=yes'), ('Active=yes', 'Active=no'), ('Class=user', 'Class=greeter'),
    ('Id=7', 'Id=--all'), ('Id=7', 'Id=')])
def test_linux_rejects_foreign_or_ambiguous_sessions(linux, old, new):
    linux.stdout = linux.stdout.replace(old, new)
    with pytest.raises(HTTPException):
        ca.build_plan('logout')


def test_linux_power_does_not_bypass_inhibitors(linux):
    for action, verb in [('shutdown', 'poweroff'), ('restart', 'reboot')]:
        assert ca.build_plan(action).argv == ('/usr/bin/systemctl', '--no-ask-password', '--check-inhibitors=yes', verb)


def test_mac_checks_console_owner_and_uses_fixed_events(monkeypatch):
    monkeypatch.setattr(ca.platform, 'system', lambda: 'Darwin')
    monkeypatch.setattr(ca.os, 'getuid', lambda: 1000)
    monkeypatch.setattr(ca, 'executable', lambda *paths: paths[0])
    monkeypatch.setattr(ca.os, 'stat', lambda path: SimpleNamespace(st_uid=2000))
    for action in ca.LABELS:
        with pytest.raises(HTTPException):
            ca.build_plan(action)
    monkeypatch.setattr(ca.os, 'stat', lambda path: SimpleNamespace(st_uid=1000))
    for action, verb in [('logout', 'log out'), ('shutdown', 'shut down'), ('restart', 'restart')]:
        assert ca.build_plan(action).argv == ('/usr/bin/osascript', '-e', 'tell application "System Events" to ' + verb)


@pytest.fixture
def windows(monkeypatch):
    monkeypatch.setattr(ca.platform, 'system', lambda: 'Windows')
    monkeypatch.setattr(ca, 'executable', lambda *paths: paths[0])
    def directory(buffer, size):
        buffer.value = 'C:/Windows/System32'
        return len(buffer.value)
    def session(pid, ptr):
        ptr._obj.value = 2
        return 1
    api = SimpleNamespace(GetSystemDirectoryW=directory, ProcessIdToSessionId=session)
    monkeypatch.setattr(ca.ctypes, 'windll', SimpleNamespace(kernel32=api), raising=False)
    return api


def test_windows_power_avoids_implicit_forced_close(windows):
    for action, flag in [('shutdown', '/s'), ('restart', '/r')]:
        assert ca.build_plan(action).argv == ('C:/Windows/System32/shutdown.exe', flag, '/t', '0')
    assert ca.build_plan('logout').argv == ('C:/Windows/System32/shutdown.exe', '/l')


def test_windows_service_cannot_log_off_another_session(windows):
    def session(pid, ptr):
        ptr._obj.value = 0
        return 1
    windows.ProcessIdToSessionId = session
    with pytest.raises(HTTPException) as error:
        ca.build_plan('logout')
    assert 'Windows service' in error.value.detail
