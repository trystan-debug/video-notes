# Video Notes

Android app for Trystan's Samsung. Record a video note, it saves to the phone first, gets a transcript, and gets filed into a topic folder by the words used.

- Videos: `Movies/VideoNotes/<folder>`
- Transcripts (.txt, same name as the video): `Documents/VideoNotes/<folder>`
- Transcripts: free on-phone engine (Android 13+) or Whisper with an OpenAI key (Settings)
- Grouping: on-phone keyword matching, no internet needed

GitHub Actions builds the APK on every push (Actions tab → latest run → `VideoNotes-apk`).
