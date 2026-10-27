#!/usr/bin/env python3
"""Verify the pinned Bark 3.0 Clean model on a host, without a phone.

Two things are checked:

1. The four model files match the SHA-256 pins in
   ``core/src/main/kotlin/dev/barkboard/core/model/BarkModel.kt`` (parsed out of the Kotlin, so a
   pin edited in one place and not the other fails here).
2. They load and transcribe through sherpa-onnx the way ``libbark_asr.so`` will call it:
   ``nemo_transducer``, int8, **no leading silence** (Clean was evaluated on unpadded audio) and
   no text post-processing beyond stripping ``<unk>``.

The clips are the 73 LibriSpeech dev-clean utterances in
``hf-internal-testing/librispeech_asr_dummy`` — the same set the model card scores. The card
reports 4.17 % WER for this int8 build; ``--max-wer`` is a little looser to allow for a different
sherpa-onnx release.

    python3 -m venv /tmp/asr-venv
    /tmp/asr-venv/bin/pip install sherpa-onnx soundfile pandas pyarrow
    /tmp/asr-venv/bin/python tools/verify-model.py --model-dir /tmp/clean --download

Exit status is non-zero when a pin fails or WER exceeds the tolerance, so this can gate a release.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import statistics
import sys
import time
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
BARK_MODEL_KT = REPO_ROOT / "core/src/main/kotlin/dev/barkboard/core/model/BarkModel.kt"
CLIPS_URL = (
    "https://huggingface.co/datasets/hf-internal-testing/librispeech_asr_dummy/resolve/main"
    "/clean/validation-00000-of-00001.parquet"
)


def pins_from_kotlin(path: Path) -> tuple[str, dict[str, str]]:
    """(source URL, {file name: sha256}) exactly as the app will use them."""
    source = path.read_text(encoding="utf-8")
    source_url = re.search(r'val SOURCE_URL = "([^"]+)"', source)
    if source_url is None:
        raise SystemExit(f"no SOURCE_URL in {path}")
    # Interpolated ("https://huggingface.co/$REPO/resolve/main"); resolve REPO for the fetch.
    repo = re.search(r'const val REPO = "([^"]+)"', source)
    url = source_url.group(1).replace("$REPO", repo.group(1)) if repo else source_url.group(1)

    pins = {
        name: digest
        for name, digest in re.findall(
            r'name = "([^"]+)"\s*,\s*sha256 = "([0-9a-fA-F]{64})"', source
        )
    }
    if not pins:
        raise SystemExit(f"no pinned files found in {path}")
    return url, pins


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def download(url: str, target: Path) -> None:
    print(f"downloading {target.name} …", flush=True)
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(target.suffix + ".part")
    with urllib.request.urlopen(url, timeout=120) as response, tmp.open("wb") as out:
        while True:
            block = response.read(1 << 20)
            if not block:
                break
            out.write(block)
    tmp.replace(target)


def verify_pins(model_dir: Path, base_url: str, pins: dict[str, str], allow_download: bool) -> bool:
    ok = True
    for name, expected in pins.items():
        path = model_dir / name
        if not path.is_file() and allow_download:
            download(f"{base_url}/{name}", path)
        if not path.is_file():
            print(f"FAIL {name}: missing (pass --download)")
            ok = False
            continue
        actual = sha256_of(path)
        if actual.lower() != expected.lower():
            print(f"FAIL {name}: sha256 {actual} != pinned {expected}")
            ok = False
        else:
            print(f"ok   {name}: {actual} ({path.stat().st_size:,} bytes)")
    return ok


def normalise(text: str) -> list[str]:
    text = text.replace("<unk>", " ").lower()
    text = re.sub(r"[^a-z0-9' ]+", " ", text)
    return text.split()


def edit_distance(reference: list[str], hypothesis: list[str]) -> int:
    previous = list(range(len(hypothesis) + 1))
    for i, ref_word in enumerate(reference, start=1):
        current = [i]
        for j, hyp_word in enumerate(hypothesis, start=1):
            current.append(
                min(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + (ref_word != hyp_word),
                )
            )
        previous = current
    return previous[-1]


def load_clips(cache: Path, limit: int | None):
    import pandas as pd
    import soundfile as sf
    import io

    if not cache.is_file():
        download(CLIPS_URL, cache)
    frame = pd.read_parquet(cache)
    if limit is not None:
        frame = frame.head(limit)

    clips = []
    for row in frame.itertuples():
        audio, rate = sf.read(io.BytesIO(row.audio["bytes"]), dtype="float32", always_2d=True)
        clips.append((row.id, audio.mean(axis=1), rate, row.text))
    return clips


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model-dir", required=True, type=Path, help="directory holding the model files")
    parser.add_argument("--download", action="store_true", help="fetch missing files from Hugging Face")
    parser.add_argument("--clips", type=int, default=None, help="limit the number of clips")
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--max-wer", type=float, default=7.0, help="fail above this WER (%%)")
    parser.add_argument("--cache-dir", type=Path, default=Path("/tmp/bark-clips"))
    args = parser.parse_args()

    base_url, pins = pins_from_kotlin(BARK_MODEL_KT)
    args.model_dir.mkdir(parents=True, exist_ok=True)

    print(f"pins from {BARK_MODEL_KT.relative_to(REPO_ROOT)}")
    if not verify_pins(args.model_dir, base_url, pins, args.download):
        return 1

    import sherpa_onnx

    clips = load_clips(args.cache_dir / "librispeech-dummy.parquet", args.clips)
    rates = {rate for _, _, rate, _ in clips}
    if rates != {16_000}:
        print(f"FAIL unexpected sample rates: {rates}")
        return 1

    started = time.monotonic()
    recogniser = sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=str(args.model_dir / "encoder.int8.onnx"),
        decoder=str(args.model_dir / "decoder.int8.onnx"),
        joiner=str(args.model_dir / "joiner.int8.onnx"),
        tokens=str(args.model_dir / "tokens.txt"),
        model_type="nemo_transducer",
        num_threads=args.threads,
    )
    print(f"loaded in {time.monotonic() - started:.1f} s")

    errors = words = 0
    latencies: list[float] = []
    samples = []
    for clip_id, samples_, rate, reference in clips:
        stream = recogniser.create_stream()
        # No padding: Bark 3.0 Clean was evaluated on the audio as it is.
        stream.accept_waveform(rate, samples_)
        began = time.monotonic()
        recogniser.decode_stream(stream)
        latencies.append(time.monotonic() - began)

        hypothesis = stream.result.text.replace("<unk>", "").strip()
        ref_words = normalise(reference)
        errors += edit_distance(ref_words, normalise(hypothesis))
        words += len(ref_words)
        if len(samples) < 5:
            samples.append((clip_id, reference, hypothesis))

    wer = 100.0 * errors / words
    for clip_id, reference, hypothesis in samples:
        print(f"\n{clip_id}\n  ref: {reference}\n  got: {hypothesis}")

    rss_mb = 0
    with Path("/proc/self/status").open() as status:
        for line in status:
            if line.startswith("VmHWM:"):
                rss_mb = int(line.split()[1]) // 1024

    print(
        json.dumps(
            {
                "clips": len(clips),
                "wer_percent": round(wer, 2),
                "tolerance_percent": args.max_wer,
                "median_decode_ms": round(statistics.median(latencies) * 1000, 1),
                "peak_rss_mb": rss_mb,
            },
            indent=2,
        )
    )
    if wer > args.max_wer:
        print(f"FAIL WER {wer:.2f}% above {args.max_wer}%")
        return 1
    print("PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
