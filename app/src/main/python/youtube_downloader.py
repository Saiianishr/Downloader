import json
import os
import re
import subprocess
import yt_dlp

def test_ffmpeg_executable(ffmpeg_path):
    if not ffmpeg_path:
        return False, "FFmpeg path is None or empty"
    if not os.path.exists(ffmpeg_path):
        return False, f"FFmpeg file does not exist at path: {ffmpeg_path}"

    try:
        try:
            os.chmod(ffmpeg_path, 0o755)
        except Exception:
            pass

        res = subprocess.run(
            [ffmpeg_path, "-version"],
            capture_output=True,
            text=True,
            timeout=10
        )
        if res.returncode == 0:
            first_line = res.stdout.splitlines()[0] if res.stdout else "ffmpeg -version succeeded"
            return True, first_line
        else:
            return False, f"Exit code {res.returncode}\nStdout: {res.stdout}\nStderr: {res.stderr}"
    except FileNotFoundError as e:
        return False, f"FileNotFoundError: {e}"
    except PermissionError as e:
        return False, f"PermissionError: {e}"
    except OSError as e:
        return False, f"OSError ({e.errno}): {e.strerror}"
    except Exception as e:
        return False, f"Exception ({type(e).__name__}): {e}"

def verify_ffmpeg(ffmpeg_path):
    ok, info = test_ffmpeg_executable(ffmpeg_path)
    return json.dumps({
        'success': ok,
        'path': ffmpeg_path,
        'info': info
    })

def find_final_output_file(output_dir, info, prepared_path, log_info):
    video_id = info.get('id') if isinstance(info, dict) else None
    candidates = []

    if isinstance(info, dict):
        if info.get('filepath'):
            candidates.append(info.get('filepath'))

        req_downloads = info.get('requested_downloads', [])
        for rd in req_downloads:
            if isinstance(rd, dict) and rd.get('filepath'):
                candidates.append(rd.get('filepath'))

    if prepared_path:
        candidates.append(prepared_path)
        base_path, _ = os.path.splitext(prepared_path)
        candidates.append(base_path + ".mp4")
        clean_base = re.sub(r'\.f\d+$', '', base_path)
        candidates.append(clean_base + ".mp4")

    log_info['candidates_checked'] = candidates

    # Check candidates for existing .mp4 file
    for cand in candidates:
        if cand and os.path.exists(cand) and cand.endswith('.mp4'):
            log_info['selected_candidate'] = cand
            return cand

    # Check candidates for any existing non-part file
    for cand in candidates:
        if cand and os.path.exists(cand) and not cand.endswith(('.part', '.ytdl', '.temp')):
            log_info['selected_candidate'] = cand
            return cand

    # Fallback: Scan output_dir
    dir_files = []
    if os.path.exists(output_dir):
        for f in os.listdir(output_dir):
            full_p = os.path.join(output_dir, f)
            if os.path.isfile(full_p):
                if f.endswith(('.part', '.ytdl', '.temp')):
                    continue
                if re.search(r'\.f\d+\.', f):
                    continue
                dir_files.append((full_p, os.path.getmtime(full_p)))

    log_info['directory_files_found'] = [f[0] for f in dir_files]
    dir_files.sort(key=lambda x: x[1], reverse=True)

    if video_id:
        for full_p, _ in dir_files:
            if video_id in full_p and full_p.endswith('.mp4'):
                log_info['selected_from_dir'] = full_p
                return full_p

    if video_id:
        for full_p, _ in dir_files:
            if video_id in full_p and not full_p.endswith(('.webm', '.m4a')):
                log_info['selected_from_dir'] = full_p
                return full_p

    for full_p, _ in dir_files:
        if full_p.endswith('.mp4'):
            log_info['selected_from_dir'] = full_p
            return full_p

    for full_p, _ in dir_files:
        if full_p.endswith(('.mp4', '.mkv', '.webm', '.3gp')):
            log_info['selected_from_dir'] = full_p
            return full_p

    return None

def download_video(url, output_dir, ffmpeg_path=None, progress_callback=None):
    ffmpeg_ok, ffmpeg_info = test_ffmpeg_executable(ffmpeg_path)
    if not ffmpeg_ok:
        return json.dumps({
            'success': False,
            'filename': None,
            'filepath': None,
            'error': f"FFmpeg Executable Verification Failed:\nPath: {ffmpeg_path}\nError: {ffmpeg_info}"
        })

    intermediate_files = []

    def hook(d):
        if d['status'] == 'downloading':
            downloaded = d.get('downloaded_bytes', 0)
            total = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
            speed = d.get('speed') or 0
            eta = d.get('eta') or 0

            percent = (downloaded / total * 100.0) if total > 0 else 0.0

            if progress_callback:
                try:
                    progress_callback.onProgress(
                        float(percent),
                        int(downloaded),
                        int(total),
                        float(speed),
                        int(eta)
                    )
                except Exception:
                    pass
        elif d['status'] == 'finished':
            filepath = d.get('filename')
            if filepath:
                intermediate_files.append(filepath)

    ydl_opts = {
        'format': 'bestvideo[height<=720]+bestaudio/best[height<=720]',
        'merge_output_format': 'mp4',
        'outtmpl': os.path.join(output_dir, '%(title)s [%(id)s].%(ext)s'),
        'ffmpeg_location': ffmpeg_path,
        'quiet': False,
        'no_warnings': False,
        'progress_hooks': [hook],
    }

    log_info = {'intermediate_files_reported': intermediate_files}

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=True)
            prepared_path = ydl.prepare_filename(info) if info else None
            log_info['prepared_filename'] = prepared_path

            final_file = find_final_output_file(output_dir, info, prepared_path, log_info)
            log_info['final_file_selected'] = final_file
            log_info['final_file_exists'] = os.path.exists(final_file) if final_file else False

            if final_file and os.path.exists(final_file):
                return json.dumps({
                    'success': True,
                    'filename': os.path.basename(final_file),
                    'filepath': final_file,
                    'debug_log': log_info,
                    'error': None
                })
            else:
                return json.dumps({
                    'success': False,
                    'filename': None,
                    'filepath': None,
                    'debug_log': log_info,
                    'error': f"Completed merged media file not found in output directory.\nDebug Log: {json.dumps(log_info)}"
                })
    except Exception as e:
        return json.dumps({
            'success': False,
            'filename': None,
            'filepath': None,
            'debug_log': log_info,
            'error': str(e)
        })
