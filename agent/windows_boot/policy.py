"""Platform-independent policy shared by the Windows helper and its tests."""
import hashlib
import hmac
import ipaddress
import json
import logging
import math
import re
import secrets
from types import SimpleNamespace
from fastapi import HTTPException
from pydantic import BaseModel, ConfigDict
from typing import Literal
from computer_actions import ComputerActions, Plan

NETWORK_SERVICE = 'LocalTaskNetwork'
BROKER_SERVICE = 'LocalTaskBroker'
PIPE_NAME = r'\\.\pipe\SolemnTreeApps.LocalTask.v1'
MAX_REQUEST = 4096
MAX_RESPONSE = 8 * 1024 * 1024


def service_sid(name):
    # Windows service SIDs use SHA-1 of the uppercase UTF-16LE service name.
    import struct
    words = struct.unpack('<5I', hashlib.sha1(name.upper().encode('utf-16le')).digest())
    return 'S-1-5-80-' + '-'.join(map(str, words))


def authorized_service_groups(groups):
    expected = service_sid(NETWORK_SERVICE)
    # Windows token group flags: ENABLED=0x4, USE_FOR_DENY_ONLY=0x10.
    return any(sid == expected and flags & 0x4 and not flags & 0x10 for sid, flags in groups)


def private_address(value):
    try:
        ip = ipaddress.IPv4Address(value)
        return any(ip in ipaddress.ip_network(n) for n in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'))
    except ValueError:
        return False


def validate_config(data):
    if not isinstance(data, dict) or set(data) != {'version', 'account', 'allowed_sid', 'host', 'interface', 'port'}:
        raise ValueError('Invalid boot-service configuration')
    if type(data['version']) is not int or data['version'] != 1 or type(data['port']) is not int or not 1 <= data['port'] <= 65535:
        raise ValueError('Invalid configuration version or port')
    if not isinstance(data['account'], str) or not data['account']:
        raise ValueError('Missing managed account')
    # Only a concrete local/domain user SID; never SYSTEM, service accounts or a group.
    if not isinstance(data['allowed_sid'], str) or not re.fullmatch(r'S-1-5-21-(?:\d+-){3}\d+', data['allowed_sid']):
        raise ValueError('A local or domain user SID is required')
    host, interface = data['host'], data['interface']
    if not isinstance(host, str) or not isinstance(interface, str) or bool(host) == bool(interface):
        raise ValueError('Select exactly one LAN address or network interface')
    if host and not private_address(host):
        raise ValueError('Use a specific private LAN IPv4 address')
    return data


def choose_address(config, interfaces):
    # Each value is a list of IPv4 address strings. Fail closed on ambiguity.
    if config['interface']:
        choices = [a for a in interfaces.get(config['interface'], []) if private_address(a)]
        return choices[0] if len(choices) == 1 else None
    return config['host'] if any(config['host'] in values for values in interfaces.values()) else None


def encode_message(key, nonce, payload):
    body = json.dumps({'nonce': nonce, 'payload': payload}, sort_keys=True, separators=(',', ':'), ensure_ascii=True)
    signature = hmac.new(key, body.encode(), hashlib.sha256).hexdigest()
    return json.dumps({'body': body, 'mac': signature}, separators=(',', ':')).encode()


def decode_message(key, raw, limit, expected_nonce=None):
    if not isinstance(raw, bytes) or len(raw) > limit:
        raise ValueError('IPC message too large')
    envelope = json.loads(raw)
    if not isinstance(envelope, dict) or set(envelope) != {'body', 'mac'}:
        raise ValueError('Invalid IPC envelope')
    body, signature = envelope['body'], envelope['mac']
    if not isinstance(body, str) or not isinstance(signature, str):
        raise ValueError('Invalid IPC signature')
    expected = hmac.new(key, body.encode(), hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, signature):
        raise ValueError('IPC authentication failed')
    data = json.loads(body)
    if not isinstance(data, dict) or set(data) != {'nonce', 'payload'} or not isinstance(data['nonce'], str) or not re.fullmatch('[0-9a-f]{64}', data['nonce']):
        raise ValueError('Invalid IPC nonce')
    if expected_nonce is not None and not hmac.compare_digest(expected_nonce, data['nonce']):
        raise ValueError('Wrong IPC reply nonce')
    return data['nonce'], data['payload']


class Request(BaseModel):
    model_config = ConfigDict(extra='forbid')
    op: Literal['snapshot', 'process', 'describe', 'prepare', 'confirm', 'health']
    body: dict


class ManagedProcesses:
    def __init__(self, native, allowed_sid, sampler):
        self.native, self.allowed_sid, self.sampler = native, allowed_sid, sampler

    def snapshot(self):
        rows = self.sampler.snapshot()
        for row in rows:
            row['manageable'] = False
            try:
                with self.native.process(row['pid'], terminate=False) as p:
                    if p.sid == self.allowed_sid and not p.critical and abs(p.created - row['created']) < 0.00001:
                        row['created'] = p.created
                        row['manageable'] = True
            except (OSError, HTTPException):
                pass
        return rows

    def command(self, cmd):
        if cmd.action != 'kill':
            raise HTTPException(400, 'Windows requires the explicit force-stop action')
        if cmd.pid <= 4:
            raise HTTPException(403, 'Core processes are protected')
        try:
            # Identity, SID, critical flag and termination all use the SAME kernel handle.
            with self.native.process(cmd.pid, terminate=True) as p:
                if p.sid != self.allowed_sid or p.critical:
                    raise HTTPException(403, 'Only the configured account’s noncritical processes may be stopped')
                if not math.isfinite(cmd.created) or p.created != cmd.created:
                    raise HTTPException(409, 'Process changed; refresh before trying again')
                p.terminate()
            logging.getLogger('localtask').info('service_action=kill pid=%s created=%s accepted', cmd.pid, cmd.created)
            return {'status': 'signal_sent', 'pid': cmd.pid}
        except OSError:
            raise HTTPException(403, 'Windows refused access, or the process has exited')


class ManagedActions(ComputerActions):
    def __init__(self, native, allowed_sid, account):
        self.native, self.allowed_sid, self.account = native, allowed_sid, account
        super().__init__(planner=self.plan, runner=self.dispatch)

    def session(self):
        sessions = [s for s in self.native.sessions() if s['sid'] == self.allowed_sid and s['id'] > 0]
        if len(sessions) != 1:
            raise HTTPException(409, 'Log off requires exactly one signed-in session for ' + self.account + '. No session or multiple sessions were found.')
        return sessions[0]

    def plan(self, action):
        if action == 'logout':
            s = self.session()
            return Plan(action, ('logout', s['id'], s['login']), self.account + ' · session ' + str(s['id']),
                        'Save work first. This logs off the selected Windows session; unsaved work may be lost. The boot service stays running.')
        if action not in ('shutdown', 'restart'):
            raise HTTPException(422, 'Unknown computer action')
        return Plan(action, (action,), 'Entire computer',
                    'Save work first. This affects all signed-in users and ends the connection. '
                    + ('LocalTask cannot turn the computer back on.' if action == 'shutdown' else
                       'The boot service starts again when Windows and the network are ready.')
                    + ' Windows may block the request if applications have unsaved work.')

    def dispatch(self, args):
        if args[0] == 'logout':
            s = self.session()
            if (s['id'], s['login']) != args[1:]:
                raise HTTPException(409, 'The signed-in session changed. No logoff request was sent.')
            self.native.logoff(s['id'])
        elif args in (('shutdown',), ('restart',)):
            self.native.power(restart=args[0] == 'restart')
        else:
            raise HTTPException(422, 'Unknown native operation')
        return SimpleNamespace(returncode=0)
