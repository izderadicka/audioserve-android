Audioserve Client for Android
============================

[Audioserve](https://github.com/izderadicka/audioserve) client for Android written in Kotlin.
Using Exoplayer and MediaController - MediaSession architecture.

**ALPHA VERSION**  Works, but some functionality still missing and might be unstable

Available features
------------------

* Browses collections and folders
* Plays files in folder one after another
* Progressive playback - starts as soon as possible
* Caches ahead next files (now 3 but should be in preferences)
* Remembers up to 100 recently listened positions
* Search (on folder names)
* Notifications
* Supports Android lifecycle - rotations, back, stop activity etc...
* Supports Android audio focus (pauses when call comes etc.)

So it's now almost same as web client (only folder info is not available), but 
with better support for Android environment.


How to install
--------------

For now you have build yourself with Android SDK or Android Studio

This is how to build debug apk from commandline:
```bash
./gradlew assembleDebug
ls app/build/outputs/apk/debug/

```


ANDROID DEVELOPMENT SUCKS!