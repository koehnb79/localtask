"""Frozen service entry point. Installer fixes code and data ACLs before starting it."""
import json
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import socket
import sys
import threading
import time

import psutil
import pywintypes
import servicemanager
import win32event
import win32service
import win32serviceutil
from fastapi import HTTPException
from pydantic import ValidationError
import uvicorn

from agent import make_app, credentials, Processes, Command
from computer_actions import PrepareAction, ConfirmAction
from .native import Native, own_sid
from .broker import BrokerDispatch
from .pipe import PipeServer, PipeClient, check_api
from .policy import NETWORK_SERVICE, BROKER_SERVICE, validate_config, choose_address, Request, ManagedProcesses, ManagedActions

DATA = Path(os.environ['ProgramData']) / 'SolemnTreeApps' / 'LocalTask'


def read_config():
    config = validate_config(json.loads((DATA / 'service.json').read_text(encoding='utf-8-sig')))
    key = bytes.fromhex((DATA / 'ipc.key').read_text().strip())
    if len(key) != 32:
        raise ValueError('Invalid local helper key')
    return config, key


def configure_logging(role):
    handler = RotatingFileHandler(DATA / 'logs' / role / (role + '.log'), maxBytes=1_000_000, backupCount=3, encoding='utf-8')
    handler.setFormatter(logging.Formatter('%(asctime)s %(levelname)s %(message)s'))
    logging.basicConfig(level=logging.INFO, handlers=[handler], force=True)




class BaseService(win32serviceutil.ServiceFramework):
    def __init__(self, args):
        super().__init__(args)
        self.stopped = win32event.CreateEvent(None, True, False, None)
        self.server = None

    def SvcStop(self):
        self.ReportServiceStatus(win32service.SERVICE_STOP_PENDING)
        win32event.SetEvent(self.stopped)
        if self.server is not None:
            self.server.should_exit = True

    def stopping(self, milliseconds=0):
        return win32event.WaitForSingleObject(self.stopped, milliseconds) == win32event.WAIT_OBJECT_0

    def SvcDoRun(self):
        try:
            self.run()
        except BaseException:
            logging.exception('Service failed')
            servicemanager.LogErrorMsg('LocalTask ' + self._svc_name_ + ' failed; see ProgramData/SolemnTreeApps/LocalTask/logs')
            # A nonzero process exit enables SCM recovery. No app payload is logged here.
            os._exit(1)


class BrokerService(BaseService):
    _svc_name_ = BROKER_SERVICE
    _svc_display_name_ = 'LocalTask Local Helper'

    def run(self):
        if own_sid() != 'S-1-5-18':
            raise RuntimeError('Local helper must run as SYSTEM')
        configure_logging('broker')
        config, key = read_config()
        native = Native()
        dispatch = BrokerDispatch(ManagedProcesses(native, config['allowed_sid'], Processes()),
                                  ManagedActions(native, config['allowed_sid'], config['account']))
        logging.info('Local helper ready; managed account SID %s', config['allowed_sid'])
        PipeServer(key, self.stopped, dispatch).run()


class NetworkService(BaseService):
    _svc_name_ = NETWORK_SERVICE
    _svc_display_name_ = 'LocalTask Network Agent'

    def address(self, config):
        interfaces = {name: [a.address for a in addresses if a.family == socket.AF_INET]
                      for name, addresses in psutil.net_if_addrs().items()}
        return choose_address(config, interfaces)

    def run(self):
        if own_sid() != 'S-1-5-19':
            raise RuntimeError('Network listener must run as LocalService, never SYSTEM/admin')
        configure_logging('network')
        config, key = read_config()
        proxy = PipeClient(key)
        creds = credentials(DATA / 'credentials')
        while not self.stopping():
            address = self.address(config)
            if not address:
                logging.info('Waiting for the configured private LAN address/interface')
                self.stopping(5000)
                continue
            try:
                proxy.call('health')
            except HTTPException:
                logging.warning('Waiting for local helper')
                self.stopping(5000)
                continue
            app = make_app(creds['token'], processes=proxy, computer_actions=proxy)
            self.server = uvicorn.Server(uvicorn.Config(app, host=address, port=config['port'],
                ssl_keyfile=str(DATA / 'credentials' / 'key.pem'),
                ssl_certfile=str(DATA / 'credentials' / 'cert.pem'), access_log=False,
                limit_concurrency=16, timeout_keep_alive=5, timeout_graceful_shutdown=5,
                loop='asyncio', http='h11', ws='none', lifespan='on', log_config=None))
            def serve():
                try:
                    self.server.run()
                except BaseException:
                    logging.exception('HTTPS listener stopped unexpectedly')
            thread = threading.Thread(target=serve, name='localtask-https', daemon=True)
            thread.start()
            logging.info('HTTPS binding %s:%s', address, config['port'])
            while thread.is_alive() and not self.stopping(3000):
                if self.address(config) != address:
                    logging.info('LAN address changed; restarting HTTPS listener')
                    break
            self.server.should_exit = True
            thread.join(20)
            if thread.is_alive():
                raise RuntimeError('HTTPS listener did not stop')
            self.server = None
            self.stopping(5000)


def main():
    if len(sys.argv) == 2 and sys.argv[1] == 'selftest':
        # Read-only packaging smoke test, run before installation.
        check_api()
        with Native().process(os.getpid()) as process:
            assert process.sid == own_sid()
            assert process.created > 0
        print('Windows imports, pipe API availability and read-only process inspection OK.')
        return
    if len(sys.argv) == 2 and sys.argv[1] == 'check':
        import ssl
        import urllib.request
        config, _ = read_config()
        interfaces = {name: [a.address for a in rows if a.family == socket.AF_INET]
                      for name, rows in psutil.net_if_addrs().items()}
        address = choose_address(config, interfaces)
        if not address:
            raise SystemExit('Configured LAN interface/address is not ready')
        certdir = DATA / 'credentials'
        context = ssl.create_default_context(cafile=str(certdir / 'cert.pem'))
        context.check_hostname = False
        creds = credentials(certdir)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context))
        for route in ('processes', 'computer-actions'):
            req = urllib.request.Request('https://' + address + ':' + str(config['port']) + '/v1/' + route,
                                         headers={'Authorization': 'Bearer ' + creds['token']})
            with opener.open(req, timeout=20) as response:
                result = json.load(response)
            if route == 'processes': print('Authenticated HTTPS + helper: ' + str(len(result['processes'])) + ' processes')
            else:
                for action in result['actions']:
                    print(action['label'] + ': ' + ('available' if action['available'] else action['reason']))
        print('No process or computer action was executed.')
        return
    if len(sys.argv) > 1 and sys.argv[1] == 'setup':
        from .setup import main as setup
        setup(sys.argv[2:])
        return
    if len(sys.argv) != 2 or sys.argv[1] not in ('network', 'broker'):
        raise SystemExit('Use the install script. Service roles: network, broker')
    role = NetworkService if sys.argv[1] == 'network' else BrokerService
    servicemanager.Initialize()
    servicemanager.PrepareToHostSingle(role)
    servicemanager.StartServiceCtrlDispatcher()
