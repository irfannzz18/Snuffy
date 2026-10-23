# Snuffy

A music player for Android. Search YouTube, play songs audio-only, make albums, and save your liked tracks.

## Features

- Search YouTube and play audio only (no video)
- Now-playing screen with a spinning black disc
- Play, pause, next, previous, shuffle and repeat
- Queue with tap-to-play
- Liked songs list with Play all and Shuffle
- Albums: create one and add songs with the **+ Add to album** button
- Play song lists from `songs.txt` or `song.txt` (each song is looked up on YouTube by name)

## How it works

- **Interface:** `app/src/main/assets/index.html`, shown inside an Android WebView
- **Playback:** ExoPlayer (Media3), Android's built-in media player
- **YouTube lookup:** yt-dlp, run inside the app through Chaquopy (embedded Python)
- **Build:** GitHub Actions builds the APK on GitHub's servers
