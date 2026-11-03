# ADB over the network to the user's phone (GrapheneOS) — step by step

**Purpose:** Wireless diagnostics (logcat/🐞 exports) without a USB cable, so that we can see the aborts
("audio start failed" after IME start, toggle aborts "no text recognized") live.
**Required:** Phone and server (<internal-host>) on the **same WLAN** (no guest network). USB debugging
does **not** need to be on; **root is not required**; no cable needed.

## 1. Preparation on the phone

1. **Unlock developer options:** Settings → About phone → tap **Build number** 7 times in a row (enter PIN if asked). *You now see:* "You are now a developer" (toast).
2. **Enable wireless debugging:** Settings → System → Developer options → turn on **Wireless debugging**. *You now see:* the toggle is on, and an IP with port appears below it.
3. **Open pairing:** Tap "Wireless debugging" → **"Pair device with pairing code"** (pairing). *You now see:* a window with a **6-digit number** (pairing code) and an **IP:PORT** entry. **The screen must stay on** — when the display locks, the pairing dialog often disappears.

## 2. Note these values and send them to us (label them separately!)

| Value | Where visible | Example |
|---|---|---|
| **A) Pairing code** (6 digits) | "Pair device with pairing code" window | `482 913` |
| **B) Pairing IP:port** | "Pair device with pairing code" window (below the code) | `192.168.1.42:37125` |
| **C) Connect IP:port** | "Wireless debugging" main screen (below the toggle) | `192.168.1.42:40133` |

**Important:** B and C have **different ports** — note both, do not mix them up.

## 3. Commands on the server (WE run these — do not type them in, for information only)

```
adb pair <B) pairing IP:port> <A) pairing code>     # one time only, code is valid only briefly
adb connect <C) connect IP:port>                    # afterwards for each session
adb devices                                         # check: device must appear as "device"
```

## 4. Quick test (WE run this)

```
adb -s <C) connect IP:port> shell getprop ro.product.model
```
Expected: model name (e.g. `Pixel 8`). Then logcat runs and we pull the 🐞 exports live.

## 5. Failure modes

| You see / what happens | Cause | Remedy |
|---|---|---|
| Server: `failed to authenticate` / code rejected | Code transcribed incorrectly or **expired** (valid only seconds to minutes) | **Close and reopen** "Pair device with pairing code" → send a fresh code; repeat |
| `adb connect` hangs after pairing / timeout | **Client isolation** on the router (devices on the WLAN may not reach each other) | Router: disable "AP/AP isolation" or "client isolation"; otherwise try the 2.4/5-GHz network of the **same router** |
| Pairing IP starts with `192.168.x.x`, server is `<internal-host>` | Phone on a **different network/guest network** | Phone and server must be on the **same router/network** (no guest WLAN, no "guests" SSID) |
| No more connect after remote start/reboot of the phone | GrapheneOS **turns wireless debugging off after reboot and after WLAN disconnection** | Repeat steps 2–3 (developer options remain unlocked) |
| Dialog disappears during pairing | **Display lock/screen off** | Keep the screen **on**; temporarily increase the display timeout |
| Unknown phone IP | shown in step 2 B/C on the phone itself | simply send the displayed values |

## 6. Security — after the diagnostics

1. Settings → System → Developer options → **Wireless debugging → paired devices → remove the server entry** (discard pairing).
2. Toggle **Wireless debugging off**.
3. Developer options may stay on (not a problem); if desired: turn "Developer options" off entirely.
4. We only log **numbers and codes** (no personal content) — the same rule-9 discipline as in the 🐞 exports.

## 7. Quick checklist

- [ ] Build number 7× → developer options present
- [ ] Wireless debugging **on**
- [ ] "Pair device with pairing code" open, screen on
- [ ] **A** pairing code, **B** pairing IP:port, **C** connect IP:port noted and sent
- [ ] Phone on the **same network** as the server (no guest network, no client isolation)
- [ ] After diagnostics: pairing removed + wireless debugging **off**

*Status: v0.1.29/v0.1.30 diagnostics. If the diagnostics are aborted, simply turn off debugging — nothing is left behind on the phone.*
