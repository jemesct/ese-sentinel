# eSE Sentinel

**Your OPPO Find X9 Pro can silently stop accepting your PIN, pattern and fingerprint — with no warning and no error. This app gives you the warning.**

An early-warning detector for a firmware bug on the OPPO Find X9 Pro (CPH2791) that kills
the phone's secure element and leaves you unable to unlock your own device until you reboot.
Plain Java, no dependencies, no root, ~17 KB.

---

## The symptom

You pick up your phone. It's completely alive — notifications render, brightness responds,
the camera opens, the clock ticks.

But:

- **Your pattern/PIN does nothing.** You draw it, it's accepted visually, then… nothing.
  No error. No "wrong pattern". No shake animation. It just doesn't proceed.
- **Your fingerprint does nothing.** The fingerprint icon may not even appear.
- **Only a reboot fixes it.**

There's no feedback because nothing *failed* — the unlock request is simply waiting for an
answer that never comes.

## The root cause

The phone has an **NXP SN220** embedded secure element (eSE) — a separate physical security
chip holding payment credentials for tap-to-pay. On this device it *also* hosts
**StrongBox** (hardware-backed keystore) and **Weaver** (the secret store your lock-screen
credential is verified against). So unlocking depends on that chip twice over.

**OPPO's secure-element HAL cuts power to the chip and restores it roughly 1 millisecond
later** between back-to-back sessions. Caught in the act:

```
07:59:11.975  SELECT ARA-M  -> 9000     chip perfectly healthy
07:59:11.996  eSEPowerOff
07:59:11.997  eSEPowerOn                <- 1 ms later
07:59:12.015  eSEPowerOff
07:59:12.016  eSEPowerOn                <- 1 ms later
07:59:12.041  SELECT ARA-M  -> 6E00     *** garbage. same applet, 66 ms later ***
07:59:12+     all subsequent commands: 6A82 / 6E00, then permanent silence
```

A JavaCard secure element needs a minimum power-off dwell time to reset cleanly. A gap that
narrow is electrically hard to distinguish from a glitch attack, and JCOP-class chips carry
tearing/glitch countermeasures that can mute the card defensively. *(That mechanism is
inference. The power flicks, the `6E00`, and the permanent mute are directly observed.)*

Then it gets worse, because **nothing above it has a timeout**:

1. `IKeyMintDevice::begin` blocks **forever** inside the StrongBox HAL — no timeout on the
   transceive into the TEE. Observed pending **14,000+ seconds**.
2. Each blocked call permanently consumes one of keystore2's **17 binder threads**. They're
   never reclaimed.
3. A Weaver read hangs **LockSettingsService** itself — every later unlock attempt queues
   behind it forever.

So the chip dies, then the thread pool drains over the next **1–3 hours** while you notice
nothing at all. When the last thread goes, you're locked out — and because
LockSettingsService is poisoned too, *even if the chip revived you'd stay locked out.* Only
a reboot clears it.

**This is OPPO's bug on two counts:** the power-cycling policy that glitches the chip, and
the absent recovery path at every layer above it.

Not every flick is fatal — across one 2-day capture, 13 of 17 stalls recovered on their own.
It's a race, and occasionally it lands badly.

## What this app does

Every 15 minutes (persisted `JobScheduler` job, survives reboots) it runs **one real
StrongBox AES-GCM operation** — the exact keystore call that hangs when the eSE dies — on a
sacrificial thread with a hard 20-second timeout.

| Result | What happens |
|---|---|
| Answers in < 1 s | Chip healthy. Nothing happens. |
| **Times out** | Alarm-priority notification, alarm sound + vibration, visible on the lock screen: **"REBOOT YOUR PHONE NOW — unlock stops working within ~1–3 hours."** Re-fires every 15 min while the chip stays dead. |
| Recovers later | Alarm clears, quiet "recovered" notification posted. |

That's the whole point: **it turns "randomly locked out of my phone" into "reboot when
convenient."** You typically get 1–3 hours of notice.

It does not fix the bug. Nobody outside OPPO can.

### Cost

- One extra eSE session per 15 min (~1–2% on top of the system's own StrongBox traffic).
- While the chip is wedged, at most **one** extra blocked keystore2 thread — probes don't
  stack (`PROBE_IN_FLIGHT` guard).

## Install

Grab `ese-sentinel.apk` from [Releases](../../releases), then:

```bash
adb install -r ese-sentinel.apk
```

Or sideload it directly on the phone. Then open **eSE Sentinel** and, once:

1. **Allow notifications** when prompted.
2. Tap **"Exempt from battery optimisation"** and allow — ColorOS kills background apps
   aggressively, and this keeps the 15-minute job alive. *Don't skip this one.*
3. Recommended: Settings → App management → eSE Sentinel → **Allow auto-launch**
   (ColorOS-specific; belt and braces for the job surviving a reboot).
4. Tap **"Probe now"** — a healthy chip shows `OK (<1000 ms)`.

## Building from source

Gradle-free — `aapt2` → `javac` → `d8` → `zipalign` → `apksigner`:

```bash
KEYSTORE_PASS=yourpassword ./build.sh
```

Expects an Android SDK at `~/Library/Android/sdk`. Generates a self-signed key on first run.
Min SDK 33, target 36, zero dependencies. The whole app is four Java files — the interesting
one is [`Prober.java`](src/pro/sparkworks/esewatch/Prober.java).

## Scope and caveats

- **Built and verified on a Find X9 Pro (CPH2791), ColorOS `16.0.10.500(EX01)`, Android 16.**
  Other OPPO/OnePlus devices with an SN220 eSE plausibly share the bug, but that's untested.
- Requires a device with **StrongBox**. Without it, key generation fails and the app reports
  `ERROR`, not `TIMEOUT`.
- No firmware newer than `16.0.10.500(EX01)` existed as of 2026-08-27.
- It's a **detector, not a fix**. If you get the alarm, reboot.

If you're affected, **report it to OPPO** — there's a ready-to-send technical summary at the
bottom of [`ROOT_CAUSE.md`](ROOT_CAUSE.md). The more reports, the likelier it gets fixed.

## How this was found

Two days of `adb` forensics, much of it on a phone that was wedged at the time — `adb` keeps
working on a locked device once the host key is trusted, which is what made live capture
possible at all. The full evidence chain, including the hypotheses that were wrong first, is
in [`ROOT_CAUSE.md`](ROOT_CAUSE.md).

## License

MIT — see [LICENSE](LICENSE). Use it, fork it, port it to your device.
