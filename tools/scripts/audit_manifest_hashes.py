#!/usr/bin/env python3
"""
Audit + repair stale SHA-1 hashes in modpack manifest JSON files.

For each manifest under MANIFEST_ROOT:
  * packForgeHash       -> compared to upstream maven .sha1 sidecar (cheap)
  * packLogoSha1        -> downloaded + hashed
  * packBackgroundSha1  -> downloaded + hashed
  * packMods[].sha1     -> downloaded + hashed (skipped if "-1")
  * packConfigs[].sha1  -> downloaded + hashed (skipped if "-1")
  * packResourcePacks[].sha1 (skipped if "-1")
  * packInitialFiles[].sha1 (skipped if "-1")

URL+SHA-1 pairs are downloaded once and cached so the two Alto manifests
share work for files they reference in common.

Run with --apply to write changes; without it, just prints a diff plan.
"""
from __future__ import annotations
import argparse, concurrent.futures, glob, hashlib, json, os, re, sys, urllib.request
from collections import defaultdict

MANIFEST_ROOT = r"E:/gitRepos/minecraft-launcher-modpacks"

HASH_CACHE: dict[str, str | None] = {}


def detect_indent(text: str) -> int:
    """Return the indent (in spaces) the first nested object uses. Defaults to 2."""
    for line in text.splitlines()[1:]:
        m = re.match(r"^( +)", line)
        if m:
            return len(m.group(1))
    return 2


def sha1_of_stream(url: str, timeout: int = 60) -> str | None:
    """Download URL, compute SHA-1 lazily. Returns hex digest lowercase or None on failure."""
    if not url:
        return None
    if url in HASH_CACHE:
        return HASH_CACHE[url]
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "mica-manifest-auditor/1.0"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            h = hashlib.sha1()
            while True:
                chunk = resp.read(65536)
                if not chunk:
                    break
                h.update(chunk)
            digest = h.hexdigest()
    except Exception as e:
        print(f"  [download fail] {url}: {e}", file=sys.stderr)
        digest = None
    HASH_CACHE[url] = digest
    return digest


def sha1_from_sidecar(url: str, timeout: int = 30) -> str | None:
    """For maven-style URLs, fetch the .sha1 sidecar instead of downloading the full artifact."""
    if not url:
        return None
    key = url + ".sha1"
    if key in HASH_CACHE:
        return HASH_CACHE[key]
    try:
        req = urllib.request.Request(key, headers={"User-Agent": "mica-manifest-auditor/1.0"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            text = resp.read().decode("ascii", errors="replace").strip().split()[0]
        digest = text.lower() if re.fullmatch(r"[0-9a-fA-F]{40}", text) else None
    except Exception:
        digest = None
    HASH_CACHE[key] = digest
    return digest


def gather_jobs(manifest: dict) -> list[tuple[str, str, str, str | None]]:
    """
    Returns a list of (field_path, url, current_sha1, sidecar_url_or_None).
    field_path is a JSONPath-ish string used to locate the value for replacement.
    """
    jobs = []
    if manifest.get("packForgeURL") and manifest.get("packForgeHash"):
        jobs.append(("$.packForgeHash", manifest["packForgeURL"], manifest["packForgeHash"], "sidecar"))
    if manifest.get("packLogoURL") and manifest.get("packLogoSha1"):
        jobs.append(("$.packLogoSha1", manifest["packLogoURL"], manifest["packLogoSha1"], None))
    if manifest.get("packBackgroundURL") and manifest.get("packBackgroundSha1"):
        jobs.append(("$.packBackgroundSha1", manifest["packBackgroundURL"], manifest["packBackgroundSha1"], None))
    for arr_key in ("packMods", "packConfigs", "packResourcePacks", "packInitialFiles"):
        for i, entry in enumerate(manifest.get(arr_key, [])):
            sha = (entry.get("sha1") or "").strip()
            url = entry.get("remote") or ""
            if not url or not sha or sha == "-1":
                continue
            jobs.append((f"$.{arr_key}[{i}].sha1", url, sha, None))
    return jobs


def resolve_correct(job) -> tuple[str, str, str, str | None]:
    """job -> (path, current, computed, source). Computed is None on download failure."""
    path, url, current, mode = job
    if mode == "sidecar":
        side = sha1_from_sidecar(url)
        if side is not None:
            return path, current, side, "sidecar"
        # fall back to streaming the artifact itself
    digest = sha1_of_stream(url)
    return path, current, digest, "download"


def patch_text(text: str, manifest: dict, replacements: list[tuple[str, str, str]]) -> str:
    """
    Apply (path, old, new) replacements to the raw manifest text by surgical
    string substitution. Avoids re-serializing the JSON, which would normalize
    whitespace and clobber any compact-array formatting the manifests use.

    Each replacement looks for the literal old hex string (case-insensitively
    matching what's in the file) bordered by quotes. SHA-1 hashes are 40 hex
    chars so collisions across unrelated fields are vanishingly unlikely, but
    we still scan first and bail if the old value isn't found uniquely in the
    raw text.
    """
    out = text
    for _path, old, new in replacements:
        # SHA-1 values are case-sensitive in JSON terms but our verify is
        # case-insensitive — so the stored value may be UPPER or lower case.
        # Build a regex that matches whatever capitalization the file uses,
        # bordered by quotes so we never overlap into adjacent data.
        pattern = re.compile(r'"(' + re.escape(old) + r')"', re.IGNORECASE)
        matches = pattern.findall(out)
        if len(matches) == 0:
            raise RuntimeError(f"Could not find {old} in file (was the file edited mid-run?)")
        if len(matches) > 1:
            raise RuntimeError(f"Found {len(matches)} matches for {old} — refusing to replace ambiguously")
        out = pattern.sub(f'"{new}"', out, count=1)
    return out


def audit_manifest(path: str, workers: int, apply: bool) -> dict:
    print(f"\n=== {path} ===")
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    manifest = json.loads(text)
    jobs = gather_jobs(manifest)
    print(f"  {len(jobs)} hashed entries to audit")

    results = []
    if jobs:
        with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as ex:
            for r in ex.map(resolve_correct, jobs):
                results.append(r)

    mismatches: list[tuple[str, str, str]] = []
    skipped = 0
    for path_field, current, computed, source in results:
        if computed is None:
            skipped += 1
            continue
        if computed.lower() != current.lower():
            mismatches.append((path_field, current, computed))

    if not mismatches:
        print(f"  OK ({skipped} skipped due to download errors)" if skipped else "  OK")
        return {"path": path, "fixed": 0, "skipped": skipped}

    print(f"  {len(mismatches)} mismatches:")
    for path_field, current, computed in mismatches[:25]:
        print(f"    {path_field}\n      old={current}\n      new={computed}")
    if len(mismatches) > 25:
        print(f"    ... and {len(mismatches)-25} more")

    if apply:
        new_text = patch_text(text, manifest, mismatches)
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(new_text)
        print(f"  -> wrote {len(mismatches)} fixes")
    return {"path": path, "fixed": len(mismatches), "skipped": skipped}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true",
                    help="Write fixes to disk. Without this flag, just print a plan.")
    ap.add_argument("-j", "--workers", type=int, default=12,
                    help="Parallel HTTP workers per manifest (default 12)")
    args = ap.parse_args()

    files = sorted(glob.glob(os.path.join(MANIFEST_ROOT, "**", "manifest*.json"), recursive=True))
    print(f"Auditing {len(files)} manifests under {MANIFEST_ROOT}")
    print(f"Mode: {'APPLY (writing changes)' if args.apply else 'DRY RUN (no writes)'}")

    summary = []
    for f in files:
        summary.append(audit_manifest(f, args.workers, args.apply))

    print("\n=== Summary ===")
    total_fixed = sum(s["fixed"] for s in summary)
    total_skipped = sum(s["skipped"] for s in summary)
    for s in summary:
        if s["fixed"] or s["skipped"]:
            print(f"  {s['path']}: {s['fixed']} fixed, {s['skipped']} skipped")
    print(f"TOTAL: {total_fixed} fixes across {sum(1 for s in summary if s['fixed'])} manifests, "
          f"{total_skipped} skipped due to download errors")


if __name__ == "__main__":
    main()
