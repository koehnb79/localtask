from collections import namedtuple
from pathlib import Path
import sys
from unittest.mock import Mock
import pytest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import metrics
from agent import make_app
from fastapi.testclient import TestClient

CPU = namedtuple('CPU', 'user nice system idle iowait guest guest_nice')
NET = namedtuple('NET', 'bytes_sent bytes_recv')
MEM = namedtuple('MEM', 'total available')


def source(monkeypatch):
    monkeypatch.setattr(metrics.psutil, 'virtual_memory', lambda: MEM(1000, 400))
    monkeypatch.setattr(metrics.psutil, 'cpu_times', lambda: CPU(10, 0, 10, 80, 0, 0, 0))
    monkeypatch.setattr(metrics.psutil, 'net_io_counters', lambda **kw: {'eth0': NET(100, 200)})
    monkeypatch.setattr(metrics.time, 'monotonic', lambda: 100)


def test_first_sample_rates_unknown_and_memory_available_based(monkeypatch):
    source(monkeypatch)
    row = metrics.Metrics().sample()
    assert row['cpu'] is None and row['upload'] is None and row['download'] is None
    assert row['memory_used'] == 600 and row['memory_total'] == 1000


def test_cpu_and_network_deltas_with_iowait_and_guest(monkeypatch):
    source(monkeypatch)
    collector = metrics.Metrics(); collector.sample()
    monkeypatch.setattr(metrics.time, 'monotonic', lambda: 105)
    # guest is included in user: total delta=100, idle+iowait delta=60 => 40%.
    monkeypatch.setattr(metrics.psutil, 'cpu_times', lambda: CPU(40, 0, 20, 130, 10, 5, 0))
    monkeypatch.setattr(metrics.psutil, 'net_io_counters', lambda **kw: {'eth0': NET(600, 1200)})
    row = collector.sample()
    assert row['cpu'] == pytest.approx(40)
    assert row['upload'] == 100 and row['download'] == 200


@pytest.mark.parametrize('network', [{'eth0': NET(1, 1)}, {'wifi': NET(99999, 99999)}])
def test_counter_reset_and_adapter_change_create_gap(monkeypatch, network):
    source(monkeypatch); collector = metrics.Metrics(); collector.sample()
    monkeypatch.setattr(metrics.time, 'monotonic', lambda: 105)
    monkeypatch.setattr(metrics.psutil, 'net_io_counters', lambda **kw: network)
    assert collector.sample()['download'] is None


def test_history_bounded_and_sessions_distinct(monkeypatch):
    source(monkeypatch); collector = metrics.Metrics(limit=2)
    for _ in range(5): collector.sample()
    assert len(collector.snapshot()['samples']) == 2
    assert collector.session != metrics.Metrics().session


def test_metrics_endpoint_authenticated_and_lifecycle_stops_thread(monkeypatch):
    source(monkeypatch); captured=[]
    class Collector(metrics.Metrics):
        def __init__(self): super().__init__();captured.append(self)
    import agent
    monkeypatch.setattr(agent, 'Metrics', Collector)
    with TestClient(make_app('x'*43, Mock())) as client:
        assert client.get('/v1/metrics').status_code == 401
        response=client.get('/v1/metrics', headers={'Authorization':'Bearer '+'x'*43})
        assert response.status_code == 200
        assert response.json()['interval'] == 5
    assert not captured[0].thread.is_alive()
