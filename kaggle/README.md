# Building noiz on Kaggle

`kaggle/build-android.sh` turns a fresh Kaggle notebook (or any Linux box) into an
APK builder: it installs JDK 17 if missing, downloads the Android SDK, accepts the
licenses and runs `:app:assembleDebug`. The single command from a notebook cell:

```bash
!bash /kaggle/working/noiz/kaggle/build-android.sh
# → /kaggle/working/noiz-debug.apk  (download from the notebook output pane)
```

## Getting the code into the notebook

Any of these work:

1. **Kaggle "Add input → GitHub repo"** — point it at the repo; the tree appears
   under `/kaggle/input/<repo>/`.
2. `!git clone https://github.com/<owner>/noiz.git /kaggle/working/noiz` — the
   token-free URL is fine if the repo is public.
3. Upload a zip of the repo in the notebook sidebar and `!unzip` it.

## Notes

* First run takes ~15–25 min: two Gradle distributions (wrapper + none), Maven
  Central fetches and the SDK download. Subsequent runs in the same session are
  fast (Gradle caches in `/root/.gradle`).
* The repo's `gradle.properties` pins a *small-machine* configuration
  (`jvmargs -Xmx1536m`, `workers.max=2`) because the usual dev box has 3 GB RAM.
  The Kaggle script overrides JVM size to 4 GB; nothing else in the repo changes.
* `local.properties` (with `sdk.dir`) is generated on the local machine and is
  git-ignored — the script exports `ANDROID_HOME` instead, so it works either way.
* If the run is killed for disk space, delete `android/.gradle` and
  `android/app/build` and rerun; the Gradle user cache (~1 GB) is the other
  candidate to clear with `rm -rf /root/.gradle/caches`.
