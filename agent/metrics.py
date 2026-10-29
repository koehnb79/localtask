"""Bounded, in-memory system history. No process arguments or credentials collected."""
from collections import deque
import threading
import time
import uuid
import psutil


class Metrics:
    def __init__(self, interval=5, limit=720):
        self.interval = interval
        self.samples = deque(maxlen=limit)
        self.lock = threading.Lock()
        self.stop_event = threading.Event()
        self.thread = None
        self.previous = None
        self.session = str(uuid.uuid4())

    def sample(self):
        now = time.monotonic()
        cpu = psutil.cpu_times()
        # Linux guest times are already included in user/nice.
        total = sum(cpu) - getattr(cpu, 'guest', 0) - getattr(cpu, 'guest_nice', 0)
        idle = cpu.idle + getattr(cpu, 'iowait', 0)
        memory = psutil.virtual_memory()
        network = psutil.net_io_counters(pernic=True)
        current = (now, total, idle, network)
        percent = upload = download = None
        if self.previous:
            old_time, old_total, old_idle, old_network = self.previous
            delta = total - old_total
            if delta > 0:
                percent = min(100.0, max(0.0, 100 * (1 - (idle - old_idle) / delta)))
            elapsed = now - old_time
            # New/disappeared/reset interfaces cause a gap instead of a false spike.
            if elapsed > 0 and network.keys() == old_network.keys():
                sent = [v.bytes_sent - old_network[k].bytes_sent for k, v in network.items()]
                recv = [v.bytes_recv - old_network[k].bytes_recv for k, v in network.items()]
                if all(v >= 0 for v in sent + recv):
                    upload, download = sum(sent) / elapsed, sum(recv) / elapsed
        self.previous = current
        row = dict(time=time.time(), cpu=percent, memory_used=memory.total-memory.available,
                   memory_total=memory.total, upload=upload, download=download)
        with self.lock:
            self.samples.append(row)
        return row

    def start(self):
        def run():
            while not self.stop_event.is_set():
                try:
                    self.sample()
                except Exception:
                    self.previous = None
                self.stop_event.wait(self.interval)
        self.thread = threading.Thread(target=run, daemon=True, name='localtask-metrics')
        self.thread.start()

    def stop(self):
        self.stop_event.set()
        if self.thread:
            self.thread.join(timeout=2)

    def snapshot(self):
        with self.lock:
            return dict(session=self.session, interval=self.interval, samples=list(self.samples))
