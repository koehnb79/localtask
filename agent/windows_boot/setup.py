"""Administrative configuration writer, called only by the installer."""
import argparse
import ctypes
import json
import os
from pathlib import Path
import re
import secrets

import win32security
from cryptography import x509
from cryptography.hazmat.primitives import serialization
from agent import credentials
from .policy import validate_config


def validate_pairing(source):
    values = {name: (source / name).read_bytes() for name in ('key.pem', 'cert.pem', 'token')}
    if not re.fullmatch(rb'[A-Za-z0-9_-]{43}', values['token'].strip()):
        raise ValueError('Invalid access token; select your existing .localtask directory')
    cert = x509.load_pem_x509_certificate(values['cert.pem'])
    key = serialization.load_pem_private_key(values['key.pem'], password=None)
    pub = lambda k: k.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    if pub(cert.public_key()) != pub(key.public_key()):
        raise ValueError('Certificate and private key do not match')
    return values


def main(argv):
    if not ctypes.windll.shell32.IsUserAnAdmin():
        raise SystemExit('Installation requires Administrator permission')
    parser = argparse.ArgumentParser()
    parser.add_argument('--account', required=True)
    parser.add_argument('--source', type=Path)
    parser.add_argument('--host', default='')
    parser.add_argument('--interface', default='')
    parser.add_argument('--port', type=int, default=8765)
    args = parser.parse_args(argv)
    # Destination is fixed, not supplied by network clients or a mutable source config.
    target = Path(os.environ['ProgramData']) / 'SolemnTreeApps' / 'LocalTask'
    sid, domain, kind = win32security.LookupAccountName(None, args.account)
    if kind != win32security.SidTypeUser:
        raise ValueError('Select a user account, not a group or service identity')
    name, domain, _ = win32security.LookupAccountSid(None, sid)
    data = validate_config(dict(version=1, account=domain + '\\' + name,
        allowed_sid=win32security.ConvertSidToStringSid(sid), host=args.host, interface=args.interface, port=args.port))
    existing = target / 'service.json'
    if existing.exists():
        previous = validate_config(json.loads(existing.read_text(encoding='utf-8-sig')))
        if data['allowed_sid'] != previous['allowed_sid']:
            raise ValueError('Changing the managed account requires a separate reviewed migration; update refused')
    certdir = target / 'credentials'
    if any(certdir.iterdir()):
        validate_pairing(certdir)
        if args.source and args.source.resolve() != certdir.resolve():
            original = validate_pairing(args.source)
            if any((certdir / name).read_bytes() != value for name, value in original.items()):
                raise ValueError('Existing service pairing differs; refusing to replace it')
    else:
        if args.source is None:
            raise ValueError('First install requires --source pointing to existing pairing credentials')
        for name, value in validate_pairing(args.source).items():
            with (certdir / name).open('xb') as f:
                f.write(value)
    ipc = target / 'ipc.key'
    if not ipc.exists():
        with ipc.open('x') as f:
            f.write(secrets.token_hex(32))
    elif not re.fullmatch('[0-9a-f]{64}', ipc.read_text().strip()):
        raise ValueError('Invalid existing IPC key')
    temp = target / 'service.json.new'
    temp.write_text(json.dumps(data, indent=2), encoding='utf-8')
    temp.replace(existing)
    print('Managed account: ' + data['account'])
    print('Certificate fingerprint: ' + credentials(certdir)['fingerprint'])
    print('Existing phone pairing retained. Access key not displayed.')
