# Root cause: eSE death by rapid power-cycling on OPPO Find X9 Pro

Investigation notes, 2026-08-25 → 2026-08-27, via `adb` on a device that was wedged at the
time of capture.

Everything in §1–§4 is **directly observed** in full (unfiltered) logcat and live device
state. §5 is labelled inference. §6 lists hypotheses that were tested and **falsified** —
included deliberately, because two of them looked convincing for a while.

```
Device      OPPO Find X9 Pro (CPH2791)
Build       CPH2791_16.0.10.500(EX01) / V16.1.0, Android 16 (BP2A.250605.015)
SoC         MediaTek
```

---

## 1. Architecture (established from the device)

- **eSE:** NXP **SN220** (`processChipType Product : SN220`), JCOP secure element.
- **Host link:** dedicated SPI, `/dev/p73`, driven from **inside the Trustonic TEE**
  (`GPMeSE-HAL` → `TEEC_InvokeCommand CMD_OMA_*`). Config: `/odm/etc/libese-nxp.conf`.
- **Two KeyMint HALs:**
  - `android.hardware.security.keymint@4.0-service.trustonic` (TEE)
  - `android.hardware.security.keymint-service.strongbox-nxp` (StrongBox, uid 2910
    `vendor_nxp_strongbox`) — this one talks to the SN220.
- **Weaver is on the same eSE** (`android.hardware.weaver-service.nxp`, an OPPO addition
  flagged `OPLUS_FEATURE_SECURITY_WEAVER`). Pattern unlock ⇒ SyntheticPasswordManager ⇒
  weaver read ⇒ the same chip. **Unlock therefore depends on the eSE twice** (weaver +
  gatekeeper/KeyMint).
- **OPPO's `GPMeSE-HAL` powers the eSE off on every last-channel close** and back on for the
  next request (`eSEPowerOff` / `eSEPowerOn`, plus `oplus_esepower_hal_service`).

That last point is the one that matters.

## 2. The death, observed in the act

Full logcat, pid 780 = SE HAL, 2026-08-27:

```
07:59:11.949  eSEPowerOn
07:59:11.975  SELECT ARA-M  -> 6F16...9000        chip perfectly healthy
07:59:11.982  READ DF20     -> ...9000            refresh tag read OK
07:59:11.996  eSEPowerOff                          last channel closed
07:59:11.997  eSEPowerOn                           1 ms LATER   <- power flick #1
07:59:12.014  SELECT CCC-DK (A000000809434343444B417631) -> 6A82
                                                   (car-key applet not installed; expected)
07:59:12.015  eSEPowerOff
07:59:12.016  eSEPowerOn                           1 ms LATER   <- power flick #2
07:59:12.041  SELECT ARA-M  -> 6E00               *** chip now answering garbage ***
                                                   (same applet answered 9000 66 ms earlier;
                                                    6E00 = CLA not supported)
07:59:12+     all subsequent SELECTs: 6A82 / 6E00, then silence. ATR reads all-zero.
07:59:22      SecureElement watchdog: openLogicalChannel -> channelNumber -1, sw 0000  (x3)
07:59:31      keystore2 IKeyMintDevice::begin for STRONGBOX blocks forever. Game over.
```

The **NFC controller half** of the SN220 stayed alive throughout (normal NCI traffic at
07:59:09 and after). Only the **eSE half** died.

Sub-2 ms power flicks are routine on this device, not exceptional: in one 31-minute window
the StrongBox KeyMint HAL alone ran a session every 60–90 s, each containing a 1–3 ms flick
between its ARA-M check and its applet SELECT. **~10 flicks in 31 minutes; only one killed
the chip.** This is a race, not a deterministic trigger.

## 3. Why it never recovers without a reboot

All observed live on the wedged device:

1. **No timeout in the KeyMint HAL.** keystore2's 17 binder threads block forever in
   `IKeyMintDevice::begin`; the HAL's transceive (`TEEC_InvokeCommand` into the TEE) has no
   timeout. **14,000+ seconds pending** observed. (The SecureElement service *does* have a
   3 s watchdog. The KeyMint HAL doesn't.)
2. **Weaver poisons LockSettingsService.** A `Weaver::getConfig` → `weaver read: 3` never
   returned. A test credential-verify issued over an hour later hung **before emitting a
   single log line** — i.e. LSS is serialized behind the dead weaver call. Every unlock
   attempt after the first queues behind it forever.
3. **Nothing restarts any of it.** The SE HAL, StrongBox HAL and `com.android.se` are all
   unkillable from shell (`setprop ctl.restart` → denied; `am force-stop` → silently refused
   on a persistent system app). keystore2 threads are never reclaimed. LSS never times out.

**Even if the chip itself revived, the phone would stay locked.** The absent recovery is a
whole chain: no HAL timeout → no thread reclaim → no LSS timeout.

### What the user experiences

```
07:59  chip dies                      (user notices nothing)
08:05  keystore requests start queueing, ~5-15 min apart,
       each permanently consuming one binder thread
~11:00 all 17 threads consumed
11:11  pattern entered -> "LockSettingsService: Verifying lockscreen credential for user 0"
       ... never returns. No success, no failure, no timeout.
```

That **~3-hour gap between the chip dying and the lockout** is precisely the window
[eSE Sentinel](README.md) exists to detect.

## 4. It is intermittent — most stalls self-heal

Across one 2-day capture there were **17 distinct stalled StrongBox operations**. **13
recovered on their own.** The chip had been intermittently failing and self-healing for over
a day before one stall became permanent.

Any correct theory has to explain both the transient stalls and the permanent one.

## 5. Interpretation (inference — flagged as such)

A secure element needs a minimum VDD-off dwell time to reset cleanly. OPPO's HAL routinely
powers the eSE off and on ~1 ms apart when sessions are back-to-back. A flick that narrow is
electrically hard to distinguish from a glitch attack, and JCOP-class chips carry
tearing/glitch countermeasures that can mute the card defensively.

The observed progression — healthy → `6E00` protocol garbage → permanent mute that survives
further power cycles but not a full reboot — fits a chip latched in a defensive or corrupted
boot state. The 13 transient stalls fit the same race landing on less critical chip-internal
state.

**This is OPPO's bug on two counts:** (a) the power-cycling policy that glitches the chip,
and (b) the missing recovery path at every layer above it. (b) is arguably the more
tractable fix.

## 6. Falsified hypotheses

Recorded so nobody re-treads them, and as calibration on the ones that remain.

| Hypothesis | How it was killed |
|---|---|
| **A Facebook system preload is the cause.** `com.facebook.services` was ANRing every few minutes, blocked in binder to keystore2 on `generateKey`, 200 s per stall, 18 times before a lockout. | **Experiment.** Removed it from the device's secondary user profiles. Result: zero such ANRs across 51 h of awake uptime (previous trigger threshold ~26 h) — and the phone locked up anyway. It was a *victim*, not a cause. |
| **Google Wallet's keyguard handler triggers it.** `tapandpay.keyguard.KeyguardDismissedIntentOperation` was the operation in flight at the moment of death. | **Data.** Checked all 17 stall onsets for Wallet / NFC / `openLogicalChannel` / `USER_PRESENT` activity in the preceding 60 s. **Only 2 of 17 had any.** Not the common driver. |
| **A failed digital-car-key enrolment corrupted eSE state.** A dormant CCC applet query recurs in Wallet traffic. | **Timing.** All such failures postdate the wedge — they're symptoms. The related app's last use was ~95 minutes before the first stall; far too loose to claim causality. The `6A82` (applet not installed) response is *expected* and harmless. |

A methodological note worth passing on: the first pass at this analysis worked from a
**filtered** logcat, and the filter didn't include the vendor tags (`GPMeSE-HAL`, `weaver`,
`OmapiTransport`) that contain the actual death. The seconds around the failure looked like
"ordinary app noise" purely as an artifact of the filter. The root cause only became visible
in the **unfiltered** log.

## 7. Report text for OPPO

> On CPH2791 / 16.0.10.500(EX01), the GPMeSE-HAL
> (`vendor.oplus.hardware.secure_element-service`) powers the SN220 eSE off and back on
> ~1 ms apart between back-to-back OMAPI sessions. On 2026-08-27 at 07:59:12 (full logcat
> available) this left the eSE in a corrupted state: ARA-M SELECT answered `9000` at
> 07:59:11.975, then `6E00` at 07:59:12.041 after two sub-2 ms power cycles, after which the
> chip went permanently mute.
>
> Consequences: (1) the KeyMint StrongBox HAL's `begin()` blocks forever — no timeout on the
> TEE transceive — exhausting all 17 keystore2 binder threads; (2) a weaver read hangs
> LockSettingsService permanently. The user cannot unlock the device by pattern or
> fingerprint, receives no error of any kind, and only a reboot recovers.
>
> Requested fixes: enforce a minimum eSE power-off dwell time (or stop cycling VDD per
> session); add a timeout plus eSE cold-reset recovery in the KeyMint and weaver HALs.

Attach a full `adb bugreport` taken while wedged if you can get one — `adb` continues to work
on a locked device once the host key is trusted.
