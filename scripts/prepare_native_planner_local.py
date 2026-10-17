#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Prepare ONE permitted local audio pair for the offline Phase 3.1 JVM harness.

Optional developer tooling, never used by the app. Requires ffmpeg/ffprobe on PATH.
No network protocols, metadata/titles in the manifest, original file copies or
outputs inside the checkout. Decoder stderr is deliberately not exported.
"""
import argparse
from decimal import Decimal, InvalidOperation
import json
from pathlib import Path
import subprocess


def microseconds(text):
    value = Decimal(str(text))
    if not value.is_finite() or value < 0 or value * 1_000_000 != (value * 1_000_000).to_integral_value():
        raise ValueError("Invalid timing value")
    return int(value * 1_000_000)


def prepare(source, destination, origin_us, seconds):
    source = source.resolve(strict=True)
    if not source.is_file() or origin_us < 0 or origin_us % 1000:
        raise ValueError("Require a local file and a nonnegative millisecond-aligned origin")
    probe = subprocess.run([
        "ffprobe", "-v", "error", "-protocol_whitelist", "file,pipe", "-select_streams", "a:0",
        "-show_entries", "stream=sample_rate:format=duration", "-of", "json", str(source),
    ], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=20, check=False)
    if probe.returncode:
        raise ValueError("Local audio probing failed; no decoder stderr exported")
    metadata = json.loads(probe.stdout)
    rate = int(metadata["streams"][0]["sample_rate"])
    duration_us = microseconds(metadata["format"]["duration"])
    if not 8000 <= rate <= 96000 or origin_us >= duration_us:
        raise ValueError("Unsupported source rate or origin")
    decode = subprocess.run([
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-n",
        "-protocol_whitelist", "file,pipe", "-ss", str(Decimal(origin_us) / 1_000_000),
        "-i", str(source), "-t", str(seconds), "-map", "0:a:0", "-vn", "-sn", "-dn",
        "-threads", "1", "-ac", "1", "-ar", str(rate), "-f", "f32le", str(destination),
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60, check=False)
    if decode.returncode:
        raise ValueError("Local PCM decoding failed; no decoder stderr exported")
    size = destination.stat().st_size
    if size < 4 or size % 4 or size > rate * 32 * 4:
        raise ValueError("Invalid or oversized local PCM")
    if origin_us + (size // 4) * 1_000_000 // rate > duration_us:
        raise ValueError("Decoded interval exceeds reported duration; no duration was fabricated")
    return {"pcm": destination.name, "sampleRate": rate, "originUs": origin_us,
            "trackDurationUs": duration_us}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--outgoing", required=True, type=Path)
    parser.add_argument("--incoming", required=True, type=Path)
    parser.add_argument("--outgoing-start-seconds", required=True)
    parser.add_argument("--window-seconds", type=int, default=30)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    checkout = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    try:
        if output.is_relative_to(checkout) or not 4 <= args.window_seconds <= 32:
            raise ValueError("Use a fresh directory OUTSIDE the checkout and a 4–32 second window")
        origin = microseconds(args.outgoing_start_seconds)
        output.mkdir(mode=0o700, parents=True, exist_ok=False)
        outgoing = prepare(args.outgoing, output / "outgoing.f32", origin, args.window_seconds)
        incoming = prepare(args.incoming, output / "incoming.f32", 0, args.window_seconds)
        (output / "pairs.json").write_text(json.dumps({"schema": 1, "pairs": [
            {"outgoing": outgoing, "incoming": incoming}]} , indent=2) + "\n", encoding="utf-8")
    except (ValueError, InvalidOperation, KeyError, IndexError, OSError, subprocess.TimeoutExpired):
        parser.exit(1, "Local fixture preparation failed. Check inputs, timing, ffmpeg/ffprobe, and a fresh external output directory. No source metadata exported.\n")
    print("Prepared one local PCM pair. Keep inputs outside source control; share only the sanitized report.")


if __name__ == "__main__":
    main()
