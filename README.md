Audioserve Client for Android
============================

[Audioserve](https://github.com/izderadicka/audioserve) client for Android written in Kotlin.
Using Exoplayer and MediaController - MediaSession architecture.

**EARLY BETA**  Generally works, most of functionality is there, but there still might be some issues

Available features
------------------

* Browses collections and folders
* Plays files in folder one after another
* Progressive playback - starts as soon as possible
* Caches ahead next files (1 - 5 as per preferences)
* Download whole or part of  folder to cache (swipe playable item to right to see button)
* Offline mode - plays only from cache
* Remembers up to 100 recently listened positions
* Search (for folder names)
* Notifications
* Supports Android lifecycle - rotations, back, stop activity etc...
* Supports Android audio focus (pauses when call comes etc.)

So it's now almost same as web client (only folder info is not available), but 
with better support for Android environment.

KISS Design Principles
-----------------

Same as rest of audioserve I tried to stick to KISS principles -  Keep it simple stupid.
For android application it was bit challenging because platform is intrictically complicated,
so it took significantly more effort to keep its simple and stupid.
KISS principles influenced features of the application, mainly:

**a)** You are either listening or browsing - if listening you stay in the audiobook folder, if 
you leave it playback stops - you can then always start when you left from recently 
listened. It also helps when not playing items while changing settings (otherwise 
synchronization will be too complex)
    
**b)** Interface is simple and focused on phone - just list of tracks/folders and play control plus few menus. 
I do not plan to make it more complicated, neither to add layouts for tablets. 
And large title fonts are intentional - as I need glasses for reading I need large fonts to work with the app without glasses.

**c)** It's intended to play cached files - primary function is to achieve continuous 
playback of the audiobook when connectivity is lost for short time periods ( like
when traveling in underground etc.).  One can play offline from cache whole audio book,
but it still cache -  it's intended to contain couple of recent audiobooks max in the cache, but not all you 
collection. So main mode of operation is online with occasionally going offline.
As caching is mandatory, it has small side effect on the seeking within current file - it's 
available only after file is fully cached - this can be theoretically improved in future,
but it's not priority now.


How to install
--------------

For now you have build yourself with Android SDK or Android Studio

This is how to build debug apk from commandline (when you have studio or SDK+tools installed):
```bash
./gradlew assembleDebug
ls app/build/outputs/apk/debug/

```

ANDROID DEVELOPMENT SUCKS!