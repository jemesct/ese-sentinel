# eSE Sentinel

On-phone early-warning detector for the Find X9 Pro secure-element death
(see `../FINDINGS_2026-08-27_session2.md`). No root, no Mac required once installed.

## What it does

Every 15 minutes (JobScheduler periodic job, survives reboots) it performs one real
StrongBox AES operation — the exact keystore call that hangs forever when the SN220 eSE
dies — with a 20-second timeout.

- **Answer in <1 s** → chip healthy, nothing happens.
- **Timeout** → alarm-priority notification (alarm sound + vibration, shows on lock
  screen): "REBOOT YOUR PHONE NOW — unlock stops working within ~1–3 hours."
  Re-fires every 15 min while the chip stays dead.
- **Recovery after a timeout** (the transient-stall case, 13/17 historically) → the alarm
  clears and a quiet "recovered" notification is posted.

Cost: one extra eSE session per 15 min (~1–2 % on top of the system's own StrongBox
traffic), and while wedged at most **one** extra blocked keystore2 thread (probes don't
stack).

## Install / setup

```bash
cd ese-sentinel
KEYSTORE_PASS=... ./build.sh    # rebuild if needed (SDK at ~/Library/Android/sdk)
adb install -r ese-sentinel.apk
adb shell am start -n pro.sparkworks.esewatch/.MainActivity
```

Then on the phone, once:
1. Allow notifications when prompted.
2. Tap **"Exempt from battery optimisation"** and allow it (ColorOS kills background
   apps aggressively; this keeps the 15-min job alive).
3. Recommended: Settings → App management → eSE Sentinel → **Allow auto-launch**
   (ColorOS-specific toggle; belt and braces for the post-reboot job).
4. Tap **"Probe now"** — should show `OK (<1000 ms)` on a healthy chip.

## Files

- `AndroidManifest.xml`, `src/` — plain-Java app, zero dependencies (min SDK 33).
- `build.sh` — gradle-free build: aapt2 → javac → d8 → zipalign → apksigner.
- `sentinel.keystore` — self-signed signing key. NOT in this repo (see .gitignore); it is kept locally and must be backed up separately, since updates to an installed app must be signed with the same key.
