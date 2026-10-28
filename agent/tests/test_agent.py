import os
import subprocess
import sys
from pathlib import Path
from unittest.mock import Mock
import platform
import pytest
import psutil
from fastapi.testclient import TestClient
from fastapi import HTTPException
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from agent import make_app, Processes, Command, initialize, credentials

TOKEN = "x" * 43
AUTH = {"Authorization": "Bearer " + TOKEN}
try:
    psutil.Process().create_time()
    REAL_PROCESSES = True
except psutil.Error:
    REAL_PROCESSES = False


def manager_for_unit_tests():
    manager = Processes.__new__(Processes)
    manager.owner = 'test-user'
    manager.protected = {0, 1, 4, os.getpid()}
    return manager


def listing_stub():
    stub = Mock()
    stub.snapshot.return_value = []
    return stub


def test_authentication_and_routes():
    client = TestClient(make_app(TOKEN, listing_stub()))
    assert client.get('/v1/processes').status_code == 401
    assert client.post('/v1/commands', json={}).status_code == 401
    assert client.get('/v1/processes', headers={'Authorization':'Bearer wrong'}).status_code == 401
    response = client.get('/v1/processes', headers=AUTH)
    assert response.status_code == 200
    assert isinstance(response.json()['processes'], list)
    assert client.get('/docs', headers=AUTH).status_code == 404


def test_schema_and_protection():
    client = TestClient(make_app(TOKEN, manager_for_unit_tests()))
    for body in [dict(pid=0,created=1,action='kill'), dict(pid=100,created=1,action='shell'),
                 dict(pid=100,created=1,action='kill',shell='anything')]:
        assert client.post('/v1/commands', headers=AUTH, json=body).status_code == 422
    response = client.post('/v1/commands', headers=AUTH,
                           json=dict(pid=os.getpid(), created=1, action='kill'))
    assert response.status_code == 403


@pytest.mark.skipif(not REAL_PROCESSES, reason="Runtime has no matching process namespace in /proc")
def test_real_child_stale_identity_then_kill():
    child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])
    try:
        try:
            proc = psutil.Process(child.pid)
        except psutil.NoSuchProcess:
            if child.poll() is None:
                pytest.skip("Runtime cannot expose the live child PID through its process namespace")
            raise
        client = TestClient(make_app(TOKEN))
        body = dict(pid=child.pid, created=proc.create_time()+100, action='kill')
        assert client.post('/v1/commands', headers=AUTH, json=body).status_code == 409
        assert child.poll() is None
        body['created'] = proc.create_time()
        assert client.post('/v1/commands', headers=AUTH, json=body).status_code == 200
        child.wait(timeout=5)
        assert client.post('/v1/commands', headers=AUTH, json=body).status_code == 404
    finally:
        if child.poll() is None:
            child.kill();child.wait()


def test_foreign_owner_denied(monkeypatch):
    manager = manager_for_unit_tests()
    fake = Mock();fake.create_time.return_value = 1.0;fake.username.return_value = 'another-user'
    monkeypatch.setattr(psutil, 'Process', lambda pid: fake)
    with pytest.raises(HTTPException) as e:
        manager.command(Command(pid=999999,created=1,action='kill'))
    assert e.value.status_code == 403
    fake.kill.assert_not_called()


def test_windows_requires_force(monkeypatch):
    manager = manager_for_unit_tests()
    fake = Mock();fake.create_time.return_value = 1.0;fake.username.return_value = manager.owner
    monkeypatch.setattr(psutil, 'Process', lambda pid: fake)
    monkeypatch.setattr(platform, 'system', lambda: 'Windows')
    with pytest.raises(HTTPException) as e:
        manager.command(Command(pid=999999,created=1,action='terminate'))
    assert e.value.status_code == 400
    fake.terminate.assert_not_called()


def test_credentials_unique_and_not_overwritten(tmp_path):
    a,b=tmp_path/'a',tmp_path/'b'
    initialize(a);initialize(b)
    first = credentials(a)
    assert len(first['fingerprint']) == 64
    assert len(first['token']) == 43
    assert first != credentials(b)
    with pytest.raises(ValueError):initialize(a)
    assert credentials(a) == first


@pytest.mark.skipif(not REAL_PROCESSES, reason="Runtime has no matching process namespace in /proc")
def test_snapshot_samples():
    manager=Processes()
    first=manager.snapshot();second=manager.snapshot()
    assert any(p['pid']==os.getpid() for p in first)
    row=next(p for p in second if p['pid']==os.getpid())
    assert row['cpu'] is not None
    assert row['manageable'] is False


def test_real_tls_authentication(tmp_path):
    import ssl
    import socket
    import threading
    import time
    import httpx
    import uvicorn
    initialize(tmp_path/'tls')
    directory=tmp_path/'tls'
    # Bind first to avoid port-selection races.
    sock=socket.socket();sock.bind(('127.0.0.1',0))
    port=sock.getsockname()[1]
    server=uvicorn.Server(uvicorn.Config(make_app(TOKEN, listing_stub()),
        ssl_certfile=str(directory/'cert.pem'),ssl_keyfile=str(directory/'key.pem'),log_level='error'))
    thread=threading.Thread(target=lambda:server.run(sockets=[sock]),daemon=True)
    thread.start()
    try:
        deadline=time.monotonic()+5
        while not server.started and time.monotonic()<deadline:time.sleep(.01)
        assert server.started
        context=ssl.create_default_context(cafile=str(directory/'cert.pem'))
        context.check_hostname=False  # Test trusts exactly the generated certificate.
        with httpx.Client(verify=context,trust_env=False) as client:
            assert client.get(f'https://127.0.0.1:{port}/v1/processes').status_code==401
            assert client.get(f'https://127.0.0.1:{port}/v1/processes',headers=AUTH).status_code==200
        with httpx.Client(trust_env=False) as client:
            with pytest.raises(httpx.ConnectError):
                client.get(f'https://127.0.0.1:{port}/v1/processes',headers=AUTH)
    finally:
        server.should_exit=True;thread.join(5);sock.close()


@pytest.mark.parametrize("case,expected", [("stale",409),("missing",404),("denied",403),("ok",200)])
def test_command_outcomes(monkeypatch,case,expected):
    manager=manager_for_unit_tests()
    proc=Mock();proc.username.return_value=manager.owner
    proc.create_time.return_value=2.0 if case=="stale" else 1.0
    if case=="denied":proc.kill.side_effect=psutil.AccessDenied(999999)
    def lookup(pid):
        if case=="missing":raise psutil.NoSuchProcess(pid)
        return proc
    monkeypatch.setattr(psutil,'Process',lookup)
    client=TestClient(make_app(TOKEN,manager))
    result=client.post('/v1/commands',headers=AUTH,json=dict(pid=999999,created=1,action='kill'))
    assert result.status_code==expected
    if case=="ok":proc.kill.assert_called_once()
    if case in ("stale","missing"):proc.kill.assert_not_called()


def test_pair_credentials_survive_separate_processes(tmp_path):
    import json
    script = str(Path(__file__).resolve().parents[1] / 'agent.py')
    subprocess.run([sys.executable, script, 'init', '--config', str(tmp_path/'persistent')],
                   check=True, capture_output=True)
    command = [sys.executable, script, 'pair', '--config', str(tmp_path/'persistent')]
    first = json.loads(subprocess.check_output(command))
    second = json.loads(subprocess.check_output(command))
    assert first == second
    assert first == credentials(tmp_path/'persistent')
