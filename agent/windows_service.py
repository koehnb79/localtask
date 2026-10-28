"""Optional pywin32 service adapter. See README before installation."""
import ctypes
import json
from pathlib import Path
import threading
import ipaddress
import win32event
import win32service
import win32serviceutil
import servicemanager
import uvicorn
from agent import make_app, credentials

class LocalTaskService(win32serviceutil.ServiceFramework):
    _svc_name_ = "LocalTask"
    _svc_display_name_ = "LocalTask Process Manager"
    _svc_description_ = "Authenticated process management for a paired Android device"

    def __init__(self, args):
        super().__init__(args)
        self.stopped = win32event.CreateEvent(None, 0, 0, None)
        self.server = None
        self.stop_requested = threading.Event()

    def SvcStop(self):
        self.ReportServiceStatus(win32service.SERVICE_STOP_PENDING)
        self.stop_requested.set()
        if self.server:
            self.server.should_exit = True
        win32event.SetEvent(self.stopped)

    def SvcDoRun(self):
        try:
            if ctypes.windll.shell32.IsUserAnAdmin():
                raise RuntimeError("Configure the service to log on as a standard user, not LocalSystem/admin")
            settings=json.loads(Path(__file__).with_name("windows-service.json").read_text())
            host=settings['host']
            ip=ipaddress.IPv4Address(host)
            if not any(ip in ipaddress.ip_network(n) for n in ['10.0.0.0/8','172.16.0.0/12','192.168.0.0/16','127.0.0.0/8']):
                raise ValueError('Use a private LAN IPv4 address')
            directory=Path(settings['config'])
            self.server=uvicorn.Server(uvicorn.Config(make_app(credentials(directory)['token']),
                host=host, port=settings.get('port',8765),
                ssl_keyfile=str(directory/'key.pem'),ssl_certfile=str(directory/'cert.pem'),
                access_log=False,limit_concurrency=16,timeout_keep_alive=5, log_config=None))
            if not self.stop_requested.is_set():
                self.server.run()
        except Exception as exc:
            servicemanager.LogErrorMsg('LocalTask startup failed: '+str(exc))
            raise

if __name__ == '__main__':
    win32serviceutil.HandleCommandLine(LocalTaskService)
