"""yt-dlp bridge called from Kotlin via Chaquopy."""
import json
import os
import time

import yt_dlp


class Cancelled(Exception):
    pass


def _base_opts():
    return {
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "nodeps": True,
        "windowsfilenames": True,
    }


def setup_ffmpeg(native_dir, work_dir):
    """Symlink libffmpeg.so / libffprobe.so to an 'ffmpeg' / 'ffprobe' bin dir.

    Returns the directory to use as yt-dlp ffmpeg_location, or None.
    """
    bin_dir = os.path.join(work_dir, "_ffmpegbin")
    os.makedirs(bin_dir, exist_ok=True)
    found = False
    for name in ("ffmpeg", "ffprobe"):
        src = os.path.join(native_dir, "lib%s.so" % name)
        dst = os.path.join(bin_dir, name)
        if os.path.exists(src):
            found = True
            if not os.path.lexists(dst):
                try:
                    os.symlink(src, dst)
                except OSError:
                    return None
    if not found or not os.path.lexists(os.path.join(bin_dir, "ffmpeg")):
        return None
    return bin_dir


def ytdlp_version():
    return yt_dlp.version.__version__


def _clean_entry(e):
    return {
        "id": e.get("id"),
        "title": e.get("title"),
        "url": e.get("url") or e.get("webpage_url"),
        "duration": e.get("duration") or 0,
        "artist": e.get("artist") or e.get("uploader"),
        "album": e.get("album"),
        "thumbnail": e.get("thumbnail"),
    }


def search(query, limit):
    opts = _base_opts()
    opts.update({"extract_flat": "in_playlist", "playlistend": limit})
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info("ytsearch%d:%s" % (limit, query), download=False)
        entries = [e for e in (info.get("entries") or []) if e]
        return json.dumps({"ok": True, "results": [_clean_entry(e) for e in entries]})
    except Exception as e:  # noqa: BLE001
        return json.dumps({"ok": False, "error": str(e)})


def get_info(url):
    opts = _base_opts()
    opts.update({"skip_download": True})
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
        return json.dumps({"ok": True, "result": _clean_entry(info)})
    except Exception as e:  # noqa: BLE001
        return json.dumps({"ok": False, "error": str(e)})


def download(url, options_json, progress):
    """options_json: dict passed to YoutubeDL. progress: Kotlin callback."""
    opts = json.loads(options_json)
    opts.update(_base_opts())
    start_time = time.time()

    def hook(d):
        if progress.isCancelled():
            raise Cancelled()
        status = d.get("status")
        if status == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
            progress.onProgress(
                int(d.get("downloaded_bytes") or 0),
                int(total),
                float(d.get("speed") or 0),
                int(d.get("eta") or 0),
            )
        elif status == "finished":
            progress.onProgress(1, 1, 0, 0)

    opts["progress_hooks"] = [hook]
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
            if info.get("_type") == "playlist":
                info = (info.get("entries") or [{}])[0]
            path = None
            for rd in info.get("requested_downloads") or []:
                if rd.get("filepath"):
                    path = rd["filepath"]
            if not path:
                path = info.get("filepath") or ydl.prepare_filename(info)
            path = _resolve_output(path, ydl, start_time)
        return json.dumps({"ok": True, "path": path, "title": info.get("title")})
    except Cancelled:
        return json.dumps({"ok": False, "cancelled": True})
    except Exception as e:  # noqa: BLE001
        if progress.isCancelled():
            return json.dumps({"ok": False, "cancelled": True})
        return json.dumps({"ok": False, "error": str(e)})


def _resolve_output(path, ydl, start_time):
    """After -x conversion the extension changes; find the real result file."""
    if path and os.path.exists(path):
        return path
    outtmpl = ydl.params.get("outtmpl", {}).get("default", "")
    directory = os.path.dirname(outtmpl) or os.getcwd()
    stem = os.path.splitext(os.path.basename(path or ""))[0]
    best, best_mtime = None, 0
    try:
        for name in os.listdir(directory):
            full = os.path.join(directory, name)
            base = os.path.splitext(name)[0]
            if stem and not (base == stem or base.startswith(stem) or stem.startswith(base)):
                continue
            mtime = os.path.getmtime(full)
            if mtime >= start_time - 2 and mtime > best_mtime:
                best, best_mtime = full, mtime
    except OSError:
        pass
    return best or path
