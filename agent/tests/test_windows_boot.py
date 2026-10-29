"""Portable policy tests. Native actions are fakes and never affect this computer."""
import copy
import json
import sys
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from fastapi import HTTPException
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from agent import Command
from windows_boot.policy import (validate_config, choose_address, private_address,
    service_sid, authorized_service_groups, encode_message, decode_message, ManagedProcesses, ManagedActions, MAX_REQUEST)
from windows_boot.broker import BrokerDispatch

SID = 'S-1-5-21-111-222-333-1001'
OTHER = 'S-1-5-21-111-222-333-1002'
CONFIG = dict(version=1, account='PC\\koehn', allowed_sid=SID, host='192.168.61.40', interface='', port=8765)


def test_service_sid_derivation_known_windows_service():
    # The well-known TrustedInstaller service SID validates endian/casing, not just format.
    assert service_sid('TrustedInstaller') == 'S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464'
    assert service_sid('localtasknetwork') == service_sid('LocalTaskNetwork')


@pytest.mark.parametrize('field,value', [('allowed_sid', 'S-1-5-18'), ('allowed_sid', 'S-1-5-32-544'),
    ('host', '0.0.0.0'), ('host', '8.8.8.8'), ('port', True), ('port', 70000), ('version', 9)])
def test_config_rejects_broad_or_invalid_targets(field, value):
    config = dict(CONFIG); config[field] = value
    with pytest.raises(ValueError): validate_config(config)


def test_configuration_cannot_inject_commands_or_ambiguous_bind():
    for patch in ({'command': 'whoami'}, {'interface': 'Wi-Fi'}, {'host': ''}):
        with pytest.raises(ValueError): validate_config({**CONFIG, **patch})


def test_dhcp_wait_change_and_ambiguity():
    cfg = validate_config({**CONFIG, 'host': '', 'interface': 'Ethernet'})
    assert choose_address(cfg, {}) is None
    assert choose_address(cfg, {'VPN': ['10.0.0.1']}) is None
    assert choose_address(cfg, {'Ethernet': ['169.254.1.2']}) is None
    assert choose_address(cfg, {'Ethernet': ['192.168.61.40']}) == '192.168.61.40'
    assert choose_address(cfg, {'Ethernet': ['192.168.61.41']}) == '192.168.61.41'
    assert choose_address(cfg, {'Ethernet': ['192.168.61.40','10.0.0.1']}) is None
    assert choose_address(CONFIG, {'Ethernet': ['192.168.61.41']}) is None


def test_ipc_signatures_nonce_binding_and_size():
    key = b'x' * 32; nonce = 'a' * 64
    raw = encode_message(key, nonce, {'op': 'snapshot', 'body': {}})
    assert decode_message(key, raw, MAX_REQUEST, nonce)[1]['op'] == 'snapshot'
    for actual_key, expected_nonce, payload, size in [
        (b'y' * 32, nonce, raw, MAX_REQUEST), (key, 'b' * 64, raw, MAX_REQUEST),
        (key, nonce, raw.replace(b'snapshot', b'process!'), MAX_REQUEST),
        (key, nonce, raw, 10), (key, nonce, b'{}', MAX_REQUEST)]:
        with pytest.raises(ValueError): decode_message(actual_key, payload, size, expected_nonce)


class Process:
    def __init__(self, sid=SID, created=1234.5, critical=False):
        self.sid, self.created, self.critical = sid, created, critical
        self.terminate = Mock()
        self.closed = False
    def __enter__(self): return self
    def __exit__(self, *args): self.closed = True


@pytest.fixture
def processes():
    process = Process()
    native = Mock(); native.process.return_value = process
    sampler = Mock(); sampler.snapshot.return_value = [{'pid': 123, 'created': 1234.5, 'manageable': True}]
    return SimpleNamespace(process=process, native=native, sampler=sampler,
        manager=ManagedProcesses(native, SID, sampler))


@pytest.mark.parametrize('sid,critical,created,status', [(OTHER,False,1234.5,403),
    ('S-1-5-18',False,1234.5,403), (SID,True,1234.5,403), (SID,False,1234.6,409)])
def test_process_ownership_critical_and_pid_reuse(processes, sid, critical, created, status):
    p = processes.process; p.sid=sid; p.critical=critical; p.created=created
    with pytest.raises(HTTPException) as exc:
        processes.manager.command(Command(pid=123, created=1234.5, action='kill'))
    assert exc.value.status_code == status
    p.terminate.assert_not_called()
    assert p.closed


def test_stop_uses_single_inspected_handle(processes):
    result = processes.manager.command(Command(pid=123, created=1234.5, action='kill'))
    assert result['status'] == 'signal_sent'
    processes.native.process.assert_called_once_with(123, terminate=True)
    processes.process.terminate.assert_called_once()
    assert processes.process.closed


def test_core_pid_and_non_force_action_never_open_process(processes):
    for pid, action in [(4, 'kill'), (123, 'terminate')]:
        with pytest.raises(HTTPException): processes.manager.command(Command(pid=pid, created=1234.5, action=action))
    processes.native.process.assert_not_called()


@pytest.mark.parametrize('sid,critical,created,expected', [(SID,False,1234.5,True),
    (OTHER,False,1234.5,False), (SID,True,1234.5,False), (SID,False,1235.5,False)])
def test_list_only_marks_verified_owned_processes_manageable(processes,sid,critical,created,expected):
    p=processes.process; p.sid=sid;p.critical=critical;p.created=created
    assert processes.manager.snapshot()[0]['manageable'] is expected
    p.terminate.assert_not_called()


def test_inaccessible_process_is_view_only(processes):
    processes.native.process.side_effect = OSError('denied')
    assert processes.manager.snapshot()[0]['manageable'] is False


@pytest.fixture
def actions():
    native = Mock()
    native.sessions.return_value = [{'sid': SID, 'id': 3, 'login': 'logon-1'}]
    return SimpleNamespace(native=native, manager=ManagedActions(native, SID, 'PC\\koehn'))


@pytest.mark.parametrize('sessions', [[], [{'sid': OTHER,'id':3,'login':'a'}],
    [{'sid':SID,'id':0,'login':'a'}], [{'sid':SID,'id':3,'login':'a'}, {'sid':SID,'id':4,'login':'b'}]])
def test_logout_rejects_absent_foreign_system_or_ambiguous_sessions(actions,sessions):
    actions.native.sessions.return_value=sessions
    with pytest.raises(HTTPException): actions.manager.prepare('logout')
    actions.native.logoff.assert_not_called()


def test_logout_session_id_reuse_does_not_log_off_new_login(actions):
    ticket=actions.manager.prepare('logout')['confirmation']
    actions.native.sessions.return_value=[{'sid':SID,'id':3,'login':'logon-2'}]
    with pytest.raises(HTTPException): actions.manager.execute(ticket)
    actions.native.logoff.assert_not_called()


def test_logout_targets_configured_user_once(actions):
    ticket=actions.manager.prepare('logout')['confirmation']
    assert actions.manager.execute(ticket)['status']=='requested'
    actions.native.logoff.assert_called_once_with(3)
    with pytest.raises(HTTPException): actions.manager.execute(ticket)
    actions.native.logoff.assert_called_once()


@pytest.mark.parametrize('action,restart', [('shutdown',False),('restart',True)])
def test_power_is_available_before_login_without_arbitrary_parameters(actions,action,restart):
    actions.native.sessions.return_value=[]
    prepared=actions.manager.prepare(action)
    actions.native.power.assert_not_called()
    actions.manager.execute(prepared['confirmation'])
    actions.native.power.assert_called_once_with(restart=restart)


def test_broker_strict_operations_and_request_models(processes,actions):
    dispatcher=BrokerDispatch(processes.manager,actions.manager)
    bad=[{'op':'shell','body':{}}, {'op':'health','body':{'command':'anything'}},
         {'op':'prepare','body':{'action':'logout','session':4}},
         {'op':'process','body':{'pid':123,'created':1234.5,'action':'kill','sid':OTHER}},
         {'op':'health','body':{},'command':'anything'}, {'op':'confirm','body':{'confirmation':'invalid'}}]
    for request in bad: assert dispatcher(request)['status']==422
    assert dispatcher({'op':'health','body':{}})['result']['ready']
    processes.process.terminate.assert_not_called()
    actions.native.power.assert_not_called()
    actions.native.logoff.assert_not_called()


def test_pipe_requires_enabled_exact_service_group_not_shared_account():
    sid = service_sid('LocalTaskNetwork')
    assert authorized_service_groups([(sid, 4)])
    assert not authorized_service_groups([('S-1-5-19', 4)])
    assert not authorized_service_groups([(sid, 0)])
    assert not authorized_service_groups([(sid, 4 | 16)])
    assert not authorized_service_groups([(service_sid('OtherService'), 4)])
