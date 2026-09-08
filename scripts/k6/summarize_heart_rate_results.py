#!/usr/bin/env python3
"""Aggregate three-run k6 summaries and flag saturation candidates."""

import json
import math
import re
import statistics
import sys
from pathlib import Path


def metric_value(summary, metric, key, default=0):
    return summary.get("metrics", {}).get(metric, {}).get("values", {}).get(key, default)


TIMER_FIELDS = {
    "heart_websocket_processing_seconds": "websocket",
    "heart_ai_detection_seconds": "aiDetection",
    "heart_ai_request_seconds": "aiRequest",
    "heart_persistence_seconds": "persistence",
    "heart_emergency_notification_seconds": "emergencyNotification",
    "fcm_send_seconds": "fcm",
}


def parse_labels(metric):
    if "{" not in metric:
        return {}
    label_text = metric.split("{", 1)[1].rsplit("}", 1)[0]
    return dict(re.findall(r'(\w+)="([^"]*)"', label_text))


def parse_actuator(path, transport_path):
    if not path.exists():
        return {}

    result = {}
    memory_by_sample = 0
    memory_max = 0
    with path.open(encoding="utf-8") as file:
        for raw_line in file:
            line = raw_line.strip()
            if line.startswith("# sampled_at"):
                memory_max = max(memory_max, memory_by_sample)
                memory_by_sample = 0
                continue
            if line == "" or line.startswith("#") or " " not in line:
                continue

            metric, raw_value = line.rsplit(" ", 1)
            try:
                value = float(raw_value)
            except ValueError:
                continue
            if not math.isfinite(value):
                continue

            name = metric.split("{", 1)[0]
            labels = parse_labels(metric)
            if name in TIMER_FIELDS and labels.get("quantile") in ("0.95", "0.99"):
                if labels.get("exception", "none") != "none":
                    continue
                if "path" in labels and labels["path"] != transport_path:
                    continue
                if name == "heart_ai_request_seconds" and labels.get("outcome", "success") != "success":
                    continue
                percentile = "P95" if labels["quantile"] == "0.95" else "P99"
                field = f"{TIMER_FIELDS[name]}{percentile}Ms"
                result[field] = max(result.get(field, 0), value * 1000)
                continue

            if name == "heart_ai_request_seconds_count" and labels.get("outcome") == "timeout":
                result["aiTimeouts"] = max(result.get("aiTimeouts", 0), value)
            elif name in ("executor_active_threads", "executor_active"):
                result["executorActiveMax"] = max(result.get("executorActiveMax", 0), value)
            elif name in ("executor_queued_tasks", "executor_queued"):
                result["executorQueuedMax"] = max(result.get("executorQueuedMax", 0), value)
            elif name == "hikaricp_connections_active":
                result["dbActiveMax"] = max(result.get("dbActiveMax", 0), value)
            elif name == "hikaricp_connections_pending":
                result["dbPendingMax"] = max(result.get("dbPendingMax", 0), value)
            elif name == "process_cpu_usage":
                result["processCpuMax"] = max(result.get("processCpuMax", 0), value)
            elif name == "jvm_gc_pause_seconds_max":
                result["gcPauseMaxMs"] = max(result.get("gcPauseMaxMs", 0), value * 1000)
            elif name == "jvm_memory_used_bytes":
                memory_by_sample += value

    result["jvmMemoryUsedMaxBytes"] = max(memory_max, memory_by_sample)
    return result


def read_runs(root):
    grouped = {}
    for summary_path in root.glob("*/*/*/vus-*/run-*/summary.json"):
        relative = summary_path.relative_to(root).parts
        target, path, route, vus_dir = relative[:4]
        key = (target, path, route, int(vus_dir.removeprefix("vus-")))
        with summary_path.open(encoding="utf-8") as file:
            summary = json.load(file)
        duration_metric = "heart_ws_ack_duration" if target == "websocket" else "heart_ai_direct_duration"
        error_metric = "heart_ws_error" if target == "websocket" else "heart_ai_direct_error"
        measure_seconds = summary.get("widyu", {}).get("measureSeconds", 0)
        measurements_acked = metric_value(summary, "heart_measurements_acked", "count")
        ai_requests = metric_value(summary, "heart_ai_direct_requests", "count")
        completed = measurements_acked if target == "websocket" else ai_requests
        run = {
            "p95": metric_value(summary, duration_metric, "p(95)"),
            "p99": metric_value(summary, duration_metric, "p(99)"),
            "avg": metric_value(summary, duration_metric, "avg"),
            "errorRate": metric_value(summary, error_metric, "rate"),
            "sent": metric_value(summary, "heart_ws_sent", "count"),
            "acked": metric_value(summary, "heart_ws_acked", "count"),
            "measurementsSent": metric_value(summary, "heart_measurements_sent", "count"),
            "measurementsAcked": metric_value(summary, "heart_measurements_acked", "count"),
            "lost": metric_value(summary, "heart_ws_lost", "count"),
            "duplicate": metric_value(summary, "heart_ws_duplicate", "count"),
            "outOfOrder": metric_value(summary, "heart_ws_out_of_order", "count"),
            "throughput": completed / measure_seconds if measure_seconds else 0,
        }
        run.update(parse_actuator(summary_path.parent / "actuator.prom", path))
        grouped.setdefault(key, []).append(run)
    return grouped


def median_rows(grouped):
    rows = []
    previous = {}
    for key in sorted(grouped):
        target, path, route, vus = key
        runs = grouped[key]
        row = {"target": target, "path": path, "route": route, "vus": vus, "runs": len(runs)}
        fields = (
            "p95", "p99", "avg", "errorRate", "sent", "acked",
            "measurementsSent", "measurementsAcked", "lost", "duplicate", "outOfOrder",
            "throughput"
        )
        actuator_fields = set().union(*(run.keys() for run in runs)) - set(fields)
        for field in fields:
            row[field] = statistics.median(run.get(field, 0) for run in runs)
        for field in actuator_fields:
            row[field] = statistics.median(run.get(field, 0) for run in runs)

        series_key = (target, path, route)
        previous_p99 = previous.get(series_key)
        row["saturationCandidate"] = bool(
            row["errorRate"] > 0
            or row["lost"] > 0
            or (previous_p99 and previous_p99 > 0 and row["p99"] >= previous_p99 * 2)
        )
        previous[series_key] = row["p99"]
        rows.append(row)
    return rows


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: summarize_heart_rate_results.py <result-directory>")
    root = Path(sys.argv[1])
    rows = median_rows(read_runs(root))
    with (root / "aggregate.json").open("w", encoding="utf-8") as file:
        json.dump(rows, file, ensure_ascii=False, indent=2)

    print("target\tpath\troute\tvus\truns\tp95_ms\tp99_ms\tthroughput\terror_rate\tsaturation_candidate")
    for row in rows:
        print(
            f"{row['target']}\t{row['path']}\t{row['route']}\t{row['vus']}\t{row['runs']}\t"
            f"{row['p95']:.3f}\t{row['p99']:.3f}\t{row['throughput']:.3f}\t{row['errorRate']:.6f}\t"
            f"{str(row['saturationCandidate']).lower()}"
        )


if __name__ == "__main__":
    main()
