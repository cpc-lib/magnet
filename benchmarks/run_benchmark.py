#!/usr/bin/env python3
"""
Automated benchmark runner for Magnet Platform (P7.2).
Measures:
- SCN-1: Burst task submission and parse throughput (TPS, p50/p95 latency)
- SCN-2: Scheduler tick latency and throughput under concurrent downloading sessions
- SCN-3: Concurrent SSE streaming connection stability
- Prometheus metrics extraction (p50/p95/p99 tick latency, error rate, queue depth)
"""

import argparse
import concurrent.futures
import json
import os
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API_BASE = "http://localhost:8080/api/v1"
PROMETHEUS_URL = "http://localhost:9090"
DEFAULT_TEST_PORT = 18080


def get_source_host():
    env_host = os.getenv("MAGNET_TEST_SOURCE_HOST")
    if env_host:
        return env_host
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("192.0.2.1", 9))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"


def http_request(url, method="GET", data=None, token=None, timeout=10):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    body = json.dumps(data).encode("utf-8") if data is not None else None
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            content = resp.read().decode("utf-8")
            return resp.status, json.loads(content) if content else {}
    except urllib.error.HTTPError as e:
        err_content = e.read().decode("utf-8")
        try:
            return e.code, json.loads(err_content)
        except Exception:
            return e.code, {"error": err_content}
    except Exception as e:
        return 0, {"error": str(e)}


def query_prometheus(query):
    encoded = urllib.parse.quote(query)
    url = f"{PROMETHEUS_URL}/api/v1/query?query={encoded}"
    status, data = http_request(url, timeout=5)
    if status == 200 and data.get("status") == "success":
        return data.get("data", {}).get("result", [])
    return []


def register_user(username, password="benchmark_password123"):
    status, data = http_request(f"{API_BASE}/auth/register", method="POST", data={"username": username, "password": password})
    if status == 201 or status == 200:
        return data.get("token")
    # Try login if already registered
    status, data = http_request(f"{API_BASE}/auth/login", method="POST", data={"username": username, "password": password})
    if status == 200:
        return data.get("token")
    raise RuntimeError(f"Failed to authenticate user {username}: status {status}, response {data}")


def run_scn1_burst_submission(token, count=30, concurrency=10, host_ip=None):
    """SCN-1: Burst parse and task submission."""
    print(f"\n[SCN-1] Running Burst Task Submission: {count} tasks, concurrency={concurrency}...")
    source_ip = host_ip or get_source_host()
    latencies = []
    task_ids = []
    success_count = 0

    def submit_one(idx):
        nonlocal success_count
        start = time.perf_counter()
        # Unique URL query to prevent parse deduplication
        url = f"http://{source_ip}:{DEFAULT_TEST_PORT}/file/bench_1m_{idx}.bin"
        status, parse_resp = http_request(f"{API_BASE}/parses", method="POST", data={"uri": url}, token=token)
        if status != 201 or "id" not in parse_resp:
            return None, time.perf_counter() - start, False

        parse_id = parse_resp["id"]
        # Create task
        task_data = {"parseId": parse_id, "fileIndices": [0], "startImmediately": True}
        t_status, task_resp = http_request(f"{API_BASE}/tasks", method="POST", data=task_data, token=token)
        elapsed = time.perf_counter() - start
        if t_status == 201 and "id" in task_resp:
            return task_resp["id"], elapsed, True
        return None, elapsed, False

    start_all = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(submit_one, i) for i in range(count)]
        for f in concurrent.futures.as_completed(futures):
            tid, elapsed, ok = f.result()
            latencies.append(elapsed)
            if ok and tid:
                success_count += 1
                task_ids.append(tid)

    total_time = time.perf_counter() - start_all
    latencies.sort()
    avg_latency = sum(latencies) / len(latencies) if latencies else 0
    p50 = latencies[int(len(latencies) * 0.50)] if latencies else 0
    p95 = latencies[int(len(latencies) * 0.95)] if latencies else 0
    tps = success_count / total_time if total_time > 0 else 0

    res = {
        "scenario": "SCN-1 Burst Submission",
        "total_tasks": count,
        "successful_tasks": success_count,
        "total_time_seconds": round(total_time, 3),
        "tps": round(tps, 2),
        "avg_latency_ms": round(avg_latency * 1000, 2),
        "p50_latency_ms": round(p50 * 1000, 2),
        "p95_latency_ms": round(p95 * 1000, 2),
    }
    print(f"  -> Total: {success_count}/{count} created in {total_time:.2f}s | TPS: {tps:.1f} | p50: {res['p50_latency_ms']}ms | p95: {res['p95_latency_ms']}ms")
    return res, task_ids


def run_scn2_scheduler_saturation(token, task_ids, timeout=60):
    """SCN-2: Observe scheduler tick latency and download throughput until completion."""
    print(f"\n[SCN-2] Running Scheduler Saturation Check ({len(task_ids)} tasks)...")
    start_time = time.time()
    completed = set()

    # Query initial tick metrics
    initial_ticks = query_prometheus('sum(magnet_scheduler_ticks_total{result="success"})')
    initial_tick_count = float(initial_ticks[0]["value"][1]) if initial_ticks else 0

    while time.time() - start_time < timeout:
        for tid in task_ids:
            if tid in completed:
                continue
            status, data = http_request(f"{API_BASE}/tasks/{tid}", token=token)
            if status == 200 and data.get("status") == "COMPLETED":
                completed.add(tid)

        if len(completed) == len(task_ids):
            break
        time.sleep(1)

    elapsed = time.time() - start_time

    # Collect Prometheus metrics post-run
    p50_query = 'histogram_quantile(0.50, sum(rate(magnet_scheduler_tick_duration_seconds_bucket[2m])) by (le))'
    p95_query = 'histogram_quantile(0.95, sum(rate(magnet_scheduler_tick_duration_seconds_bucket[2m])) by (le))'
    p99_query = 'histogram_quantile(0.99, sum(rate(magnet_scheduler_tick_duration_seconds_bucket[2m])) by (le))'

    p50_res = query_prometheus(p50_query)
    p95_res = query_prometheus(p95_query)
    p99_res = query_prometheus(p99_query)

    tick_p50_ms = float(p50_res[0]["value"][1]) * 1000 if p50_res and p50_res[0]["value"][1] != "NaN" else 0
    tick_p95_ms = float(p95_res[0]["value"][1]) * 1000 if p95_res and p95_res[0]["value"][1] != "NaN" else 0
    tick_p99_ms = float(p99_res[0]["value"][1]) * 1000 if p99_res and p99_res[0]["value"][1] != "NaN" else 0

    final_ticks = query_prometheus('sum(magnet_scheduler_ticks_total{result="success"})')
    final_tick_count = float(final_ticks[0]["value"][1]) if final_ticks else 0

    res = {
        "scenario": "SCN-2 Scheduler Saturation",
        "tasks_completed": len(completed),
        "total_tasks": len(task_ids),
        "completion_duration_seconds": round(elapsed, 2),
        "scheduler_ticks_executed": int(final_tick_count - initial_tick_count),
        "scheduler_tick_p50_ms": round(tick_p50_ms, 2),
        "scheduler_tick_p95_ms": round(tick_p95_ms, 2),
        "scheduler_tick_p99_ms": round(tick_p99_ms, 2),
    }
    print(f"  -> {len(completed)}/{len(task_ids)} completed in {elapsed:.2f}s | Scheduler p50: {res['scheduler_tick_p50_ms']}ms | p95: {res['scheduler_tick_p95_ms']}ms")
    return res


def run_scn3_sse_streaming(token, task_id, client_count=30, listen_seconds=5):
    """SCN-3: Concurrent SSE streaming connections."""
    print(f"\n[SCN-3] Running Massive SSE Connection Test ({client_count} concurrent streams)...")
    connected = 0
    received_events = 0
    errors = 0

    def stream_client():
        nonlocal connected, received_events, errors
        url = f"{API_BASE}/tasks/{task_id}/events"
        req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}", "Accept": "text/event-stream"})
        try:
            with urllib.request.urlopen(req, timeout=listen_seconds + 3) as resp:
                if resp.status == 200:
                    connected += 1
                start = time.time()
                while time.time() - start < listen_seconds:
                    line = resp.readline()
                    if line:
                        received_events += 1
                    else:
                        break
        except Exception:
            errors += 1

    start_time = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=client_count) as executor:
        futures = [executor.submit(stream_client) for _ in range(client_count)]
        concurrent.futures.wait(futures)

    duration = time.perf_counter() - start_time
    # Query Prometheus SSE connection gauge
    sse_gauge = query_prometheus("magnet_sse_connections")
    gauge_val = float(sse_gauge[0]["value"][1]) if sse_gauge else 0

    res = {
        "scenario": "SCN-3 Massive SSE Streaming",
        "clients": client_count,
        "successfully_connected": connected,
        "total_events_received": received_events,
        "disconnect_errors": errors,
        "duration_seconds": round(duration, 2),
        "post_test_active_sse_gauge": gauge_val,
    }
    print(f"  -> Connected: {connected}/{client_count} | Total events: {received_events} | Errors: {errors} | Post-gauge: {gauge_val}")
    return res


def main():
    parser = argparse.ArgumentParser(description="Magnet Platform Benchmark Runner")
    parser.add_argument("--tasks", type=int, default=20, help="Number of tasks for SCN-1")
    parser.add_argument("--concurrency", type=int, default=10, help="Concurrency for SCN-1")
    parser.add_argument("--sse-clients", type=int, default=20, help="SSE clients for SCN-3")
    parser.add_argument("--output", type=str, default="benchmarks/results/benchmark_run.json", help="Output JSON path")
    args = parser.parse_args()

    os.makedirs(os.path.dirname(args.output), exist_ok=True)

    print("=" * 60)
    print("Magnet Platform P7.2 Benchmark Suite")
    print(f"API Target: {API_BASE}")
    print(f"Prometheus Target: {PROMETHEUS_URL}")
    print("=" * 60)

    # 1. Register test user
    test_user = f"bench_user_{int(time.time())}"
    token = register_user(test_user)
    print(f"Authenticated benchmark user: {test_user}")

    host_ip = get_source_host()
    print(f"Using host test IP for containers: {host_ip}")

    # 2. Run SCN-1
    scn1_res, task_ids = run_scn1_burst_submission(token, count=args.tasks, concurrency=args.concurrency, host_ip=host_ip)

    # 3. Run SCN-2
    scn2_res = run_scn2_scheduler_saturation(token, task_ids)

    # 4. Run SCN-3
    sample_task_id = task_ids[0] if task_ids else None
    if sample_task_id:
        scn3_res = run_scn3_sse_streaming(token, sample_task_id, client_count=args.sse_clients)
    else:
        scn3_res = {}

    report = {
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
        "user": test_user,
        "scn1_burst_submission": scn1_res,
        "scn2_scheduler_saturation": scn2_res,
        "scn3_sse_streaming": scn3_res,
    }

    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)

    print("\n" + "=" * 60)
    print(f"Benchmark finished! Results saved to: {args.output}")
    print("=" * 60)


if __name__ == "__main__":
    main()
