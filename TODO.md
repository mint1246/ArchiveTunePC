QUICK ONES TO BE DONE FIRST - WINDOW TITLE BAR SHOULD BE MERGED WITH THE APP, IT MAKES THE APP SEEM VERY NOT NATIVE 
1. COOKIE REFRESHING - DONE (2026-08-15): desktop no longer hard-expires sessions on the browser cookie's expirationDate. It now re-validates against the network (like Android, which never proactively expires) and only marks the session expired when Google actually rejects the cookie (401/403 or "not logged in"). Expired sessions surface the existing "Import session" prompt in Settings. Transient/network failures keep the last verified session for offline use.
2. PLAYLIST DOWNLOADING, SONGS CAN BE DOWNLOADED BUT NOT FULL PLAYLISTS OR SONGS IN THE PLAYLIST SCREEN.
3. CACHING - UNCERTAIN IF THIS IS ALREADY IMPLEMENTED BUT IF A SONG IS DOWNLOADED OR CACHED IT SHOULD INSTANTLY PLAY
4. BETTER LYRICS - LYRICS KIND OF SUCK RIGHT NOW, BOTH COMPARED TO THE ANDROID APP AND IN GENERAL. THE GOAL SHOULD BE LYRICS VERY SIMILAR TO Spicetify's SpicyLyrics, WHICH IS A VERY GOOD LYRICS PLUGIN FOR SPOTIFY WITH BEAUTIFUL BACKGROUNDS, INFACT, THE DESIGN SHOULD BE NEARLY EXACT.
5. MORE SETTINGS - SETTINGS BARELY ACTUALLY HAS ANYTHING COMPARED TO THE ANDROID APP.



THIS LIST MAY BE UPDATED AS I THINK OF MORE THINGS, BUT THESE ARE THE MOST IMPORTANT THINGS TO BE DONE FIRST.
ADDITIONALLY, NOTES MAY BE ADDED TO WHAT YOU'RE DOING LIVE IF I AM MONITORING AND NOTICE SOMETHING YOU SHOULD NOT BE DOING OR SOMETHING YOU SHOULD BE DOING.
IF YOU NEED CLARIFICATION DO NOT EMIT AN EOS TOKEN, INSTEAD CREATE A SCRIPT TO MONITOR THIS FILE FOR CHANGES AND ALERT YOU WHEN SOMETHING IS ADDED OR CHANGED AND RUN IT UNTIL I CHANGE THE FILE. DO NOT USE THE QUESTION TOOL EITHER IF AVAILABLE.

Notes:

Forgot to mention, i cloned spicylyrics for you in .temp/spicy-lyrics
Can you compile it often as I said? I'd like to test it
Lyrics look better certainly, and the background is nice, but its not nearly as good as SpicyLyrics, there are no animations, AT ALL, in general throughout the app and here. 
Also the new title bar SUCKS, trying to drag the window around glitches it to 12 kilometers away basically and not even in the direction i did, and it looks terrible? It doesnt look integrated with the app at all, nor is it consistent between screens like lyrics screen? I more meant like a browser where the buttons are part of the tab area or similar, you know what I mean?

can we get a compilation in? also can you get a background terminal with a script monitoring this file so that you insta respond when possible
DO NOT STOP

can you try 1 more time to build? i tried changing to full access, if not please tell me how to build myself additionally try using opencode to build it.

also remember to resetup the file watch thing every time it gets used


> Task :desktopApp:compileKotlin FAILED
e: file:///C:/Users/mint/Documents/ArchiveTunePC/desktopApp/src/main/kotlin/moe/rukamori/archivetune/desktop/ui/ArchiveTuneDesktopApp.kt:455:34 Argument type mismatch: actual type is '() -> Any', but '() -> Unit' was expected.

[Incubating] Problems report is available at: file:///C:/Users/mint/Documents/ArchiveTunePC/build/reports/problems/problems-report.html

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':desktopApp:compileKotlin' (registered by plugin 'org.jetbrains.kotlin.jvm').
> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.btapi.BuildToolsApiCompilationWork
   > Compilation error. See log for more details

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights from a Build Scan (powered by Develocity).
> Get more help at https://help.gradle.org.

BUILD FAILED in 14s
40 actionable tasks: 6 executed, 34 up-to-date
Configuration cache entry reused.
please do try at the very least opencode2, i dont know if it even will work, I APPROVE of you doing it its MY PC
