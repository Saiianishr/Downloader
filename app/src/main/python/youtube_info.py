import json
import yt_dlp

def get_video_info(url):
    ydl_opts = {
        'quiet': True,
        'no_warnings': True,
        'skip_download': True,
    }
    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(url, download=False)

        title = info.get('title', 'Unknown Title')

        duration_secs = info.get('duration')
        if duration_secs is not None:
            mins, secs = divmod(int(duration_secs), 60)
            hours, mins = divmod(mins, 60)
            if hours > 0:
                duration_str = f"{hours}:{mins:02d}:{secs:02d}"
            else:
                duration_str = f"{mins}:{secs:02d}"
        else:
            duration_str = "N/A"

        uploader = info.get('uploader') or info.get('channel') or info.get('uploader_id') or 'Unknown'

        formats = info.get('formats', [])
        heights = set()
        audio_formats = set()

        for f in formats:
            vcodec = f.get('vcodec', 'none')
            acodec = f.get('acodec', 'none')

            height = f.get('height')
            if vcodec != 'none' and height:
                heights.add(int(height))

            if acodec != 'none':
                ext = f.get('ext') or 'audio'
                abr = f.get('abr')
                if abr:
                    audio_formats.add(f"{ext} ({int(abr)} kbps)")
                else:
                    audio_formats.add(ext)

        sorted_heights = [f"{h}p" for h in sorted(heights, reverse=True)] if heights else ["N/A"]
        sorted_audio = sorted(list(audio_formats)) if audio_formats else ["N/A"]

        result = {
            "title": title,
            "duration": duration_str,
            "uploader": uploader,
            "video_heights": sorted_heights,
            "audio_formats": sorted_audio
        }
        return json.dumps(result)
