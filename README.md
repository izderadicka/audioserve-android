Audioserve Client for Android
============================
[![Build Status](https://travis-ci.org/izderadicka/audioserve-android.svg?branch=master)](https://travis-ci.org/izderadicka/audioserve-android)

[audioserve](https://github.com/izderadicka/audioserve) client for Android written in Kotlin.
Using Exoplayer and MediaController - MediaSession architecture.

**BETA 2**  Works, tested on my mobile for months, no significant issues I'm aware of



Available features
------------------

* Browses collections and folders
* Plays files in folder one after another
* Progressive playback - starts as soon as possible
* Advanced playback features - skip silence and playback speed and pitch (in pull up player bar)
* Caches ahead next n files (1 - 5 as per preferences)
* Aggressively caches server responses (use reload to force latest data)
* Downloads part of or whole folder to cache (swipe playable item to left to see download button)
* Offline mode - plays only from cache
* Remembers up to 100 recently listened positions
* Bookmarks (on folder or file)
* Search (for folder names)
* Folder details - with picture and text (if present in the folder) and summary
* Intenet search - search author, boook ...  (uses folder name and optional prefix to search on google)
* Notifications - Media notification with controls, plus notification for other features (sleep timer, downloads)
* Supports Android lifecycle - rotations, back, stop activity etc...
* Supports Android audio focus (pauses when call comes in etc.)
* Advanced playback features - playback speed, skip silence, boost volume
* Advanced FastForward >> /Rewind << - when click  skips + 30s/-15s, but when pressed for longer time plays with 3x / or "pseudo plays back" - skips back 4s, then plays 1s repeatably


KISS Design Principles
-----------------

Same as in rest of [audioserve](https://github.com/izderadicka/audioserve) I tried to stick to KISS principles -  
Keep It Simple Stupid.
For android application it was bit challenging because platform is intrinsically complicated,
so it took significantly more effort to keep its simple and stupid.
KISS principles influenced features of the application, mainly:

**a)** You are either listening or browsing - if listening you stay in the audiobook folder, if 
you leave it playback stops - you can then always start when you left from recently 
listened list. This also helps while changing settings (otherwise 
synchronization with playing item will be too complex)
    
**b)** Interface is simple and focused on phone device - just list of tracks/folders and playback controls plus few menus.
I do not plan to make it more complicated, neither to add layouts for tablets. 
And large title fonts are intentional - as I need glasses for reading I appreciate large fonts 
when working with the app without glasses.

**c)** It's intended to play cached files - primary function is to achieve continuous 
playback of an audiobook when connectivity is lost for short time periods (like
when traveling in the underground etc.).  You can play offline from cache whole audiobook,
but it's still in the cache -  it's intended to contain several of recently listned audiobooks, 
but not your whole collection. So main mode of operation is online with occasionally going offline.
As caching is mandatory, it has small side effect on the seeking within current file - it's 
available only after the file is fully cached - this can be theoretically improved in the future,
but it's not priority now.

**d)** It's designed to work with audiobooks split to files by chapters or audiobooks with chapters metadata(m4b or similar), 
so typical chapter/file has duration between 10 - 90 minutes. Anything else will be suboptimal - too short files will not assure
appropriate cache ahead (as caching is done by files). Too big files without chapters will be hard to navigate, slow to transcode,
plus there is hard limit of 250MB per file/chapter (for security reasons - this limit is on android client side, server has not limit).



How to install
--------------

I provide built .apk file signed with dummy certificate on 
[github releases page](https://github.com/izderadicka/audioserve-android/releases).

You can download to Android device and install it there 
(provided you have allowed Unknown Sources in Security settings).

Supported Android versions are from 5.0 Lollipop on (API level 21).

You can also build it yourself with Android Studio - just checkout the project from github and
open in Android Studio.

### Insecure connection to server
I added `android:usesCleartextTraffic="true"` into application manifest, so it should be possible
to use just plain unencrypted http connection to audioserve server (but indeed this is not recommended
if server is accessible through public Internet). However I have some reports, that it is not working
on some phones (not sure why as emulated Android 9 works fine), so secure https connection would
be only option there.



License
-------

[MIT](https://opensource.org/licenses/MIT) 
