# Generated decoder fixtures

These files contain generated 997 Hz sine tones (7 seconds), not music recordings.
They were generated with FFmpeg 7.1.5; no third-party audio or model is included.

```sh
ffmpeg -f lavfi -i 'sine=frequency=997:sample_rate=48000:duration=7' -ac 2 -c:a aac -b:a 96k -movflags +faststart tone-48000-stereo.m4a
ffmpeg -f lavfi -i 'sine=frequency=997:sample_rate=44100:duration=7' -ac 1 -c:a flac tone-44100-mono.flac
```

Encoded bytes may vary with FFmpeg version; tests use measured codec output, not
bit-exact compressed encodings. Tests copy assets into app-private temporary files
and delete them afterward. No network, account, storage permission, or playback
player is needed. These assets are included only in the instrumentation test APK.
