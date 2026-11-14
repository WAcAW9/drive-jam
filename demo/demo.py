"""Record the Drive Jam demo video, fully scripted.

Drives the Pleos Connect Virtual Console REST API on a timeline, records the emulator screen,
takes the app's own audio (WAV written by the debug build), then muxes video + audio + captions.

Prereqs: Pleos emulator running, debug APK installed, `adb forward tcp:8088 tcp:8088`, ffmpeg on PATH.
Usage:   python demo/demo.py            (writes demo/out/drive-jam-demo.mp4)
"""
import base64
import os
import subprocess
import threading
import time
import urllib.request
from pathlib import Path

ADB = os.environ.get("ADB", str(Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe"))
CONSOLE = "http://127.0.0.1:8088"
PKG = "com.drivejam.app"
ACT = f"{PKG}/com.drivejam.MainActivity"
OUT = Path(__file__).parent / "out"
FONT = "C\\:/Windows/Fonts/malgunbd.ttf"  # escaped for ffmpeg drawtext
TASKBAR_ICON = (1482, 1370)  # Drive Jam icon in the Pleos taskbar (2560x1440)
DURATION = 100
# Show only the Drive Jam panel (the console's own driving sim drives the cluster speedometer, which would
# disagree with the scripted speed); 1920x1080 recording -> app panel -> padded 1080p.
CROP = "crop=1250:705:640:66,scale=-2:940,pad=1920:1080:(ow-iw)/2:0:color=0x0E1116"  # captions go in the 140px strip

# (start, end, caption) — seconds from recording start
CAPTIONS = [
    (0, 6, "출발 전 · 누구와 '친구들', 기분 '피곤해'"),
    (6, 12, "잼 시작 · 차가 지금 이 드라이브에 맞는 곡을 고릅니다"),
    (12, 20, "말하듯 요청 · '여름 바다 시티팝으로' → 장르가 완전히 바뀝니다"),
    (20, 30, "출발 · 차속(주행거리 변화로 계산)에 따라 템포가 움직입니다"),
    (30, 39, "차선 변경 · 깜빡이 = 하이햇, 핸들 = 필터와 소리 방향"),
    (39, 49, "비가 온다 · 와이퍼 Low → High = 빗소리가 촘촘해집니다"),
    (49, 58, "마음에 안 들면 '다음 곡' · 다른 장르의 새 곡으로 (스티어링 휠 버튼도 됩니다)"),
    (58, 64, "터널 진입 · 전조등 ON = 밤 → 조금 차분하게"),
    (64, 72, "고속도로 100 km/h · 템포에는 상한, 소리는 먹먹하게"),
    (72, 78, "앞차 급정거 · 브레이크 + 차속 급감 → 곡이 '멈칫'했다가 이어집니다"),
    (78, 86, "주행 중에는 버튼 하나로 · '더 차분하게'"),
    (86, 94, "도착 · 감속하고 기어 P — 잼이 끝납니다"),
    (94, 100, "드라이브 잼 — 내 운전이 그대로 음악이 됩니다"),
]

# speed keyframes (t, km/h); linear in between
SPEED = [(0, 0), (20, 0), (28, 50), (64, 50), (70, 100), (72, 100), (73.5, 40), (77, 55), (86, 55), (92, 0), (100, 0)]


def odometer_now():
    import json
    with urllib.request.urlopen(CONSOLE + "/vehicle/state", timeout=5) as r:
        return float(json.load(r)["odometer"]["traveled_distance_km"])


def put(path):
    req = urllib.request.Request(CONSOLE + path, method="PUT")
    urllib.request.urlopen(req, timeout=5).read()


def adb(*args, check=False):
    return subprocess.run([ADB, *args], capture_output=True, text=True, check=check, encoding="utf-8", errors="replace")


def speed_at(t):
    for (t0, v0), (t1, v1) in zip(SPEED, SPEED[1:]):
        if t0 <= t <= t1:
            return v0 + (v1 - v0) * (t - t0) / (t1 - t0) if t1 > t0 else v1
    return SPEED[-1][1]


def steering_ramp(t0, peak, up, hold, down):
    """Schedule steering 0 → peak → 0 as (t, action) pairs."""
    acts, n = [], 6
    for i in range(1, n + 1):
        acts.append((t0 + up * i / n, f"/vehicle/controls?steering={peak * i / n:.0f}"))
    for i in range(1, n + 1):
        acts.append((t0 + up + hold + down * i / n, f"/vehicle/controls?steering={peak * (1 - i / n):.0f}"))
    return acts


def intent(*extras):
    adb("shell", "am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
        "-f", "0x10200000", "-n", ACT, *extras)


def command(extra):
    """Deliver a play/stop command to the running activity (singleTop -> onNewIntent)."""
    adb("shell", "am", "start", "-f", "0x30000000", "-n", ACT, "--ez", extra, "true")


def bring_app_to_front():
    for _ in range(4):
        if "drivejam" in adb("shell", "dumpsys", "window").stdout.split("mCurrentFocus", 1)[-1][:200]:
            return
        adb("shell", "input", "tap", *map(str, TASKBAR_ICON))
        time.sleep(2.5)
    raise SystemExit("could not bring Drive Jam to the front")


def main():
    OUT.mkdir(exist_ok=True)
    try:
        put("/vehicle/gear?gear=Park")
    except OSError as e:  # 503 emulator_inactive: the Virtual Console page must be open in a browser
        raise SystemExit(f"Virtual Console not reachable ({e}). Open http://127.0.0.1:8088/ in a browser first.")
    # 1) reset the car
    for p in ["/vehicle/gear?gear=Park", "/vehicle/turn-signal?mode=Off", "/vehicle/body?front_wiper=Off",
              "/vehicle/lights?feature=headlamp_mode&value=Off", "/vehicle/controls?brake=0",
              "/vehicle/controls?steering=0", "/vehicle/controls?speed=0"]:
        put(p)

    # 2) fresh app in demo mode
    adb("shell", "am", "force-stop", PKG)
    intent("--ez", "demo", "true", "--es", "company", "FRIENDS", "--es", "feeling", "TIRED")
    time.sleep(4)
    adb("shell", "input", "keyevent", "111")  # ESC: close any system popup (e.g. the profile menu)
    bring_app_to_front()
    time.sleep(1)

    # 3) timeline
    acts = [
        (6, "PLAY"),
        (12, "REQ:여름 바다 시티팝으로"),
        (20, "/vehicle/gear?gear=Drive"),
        (30, "/vehicle/turn-signal?mode=BlinkLeft"),
        (36, "/vehicle/turn-signal?mode=Off"),
        (39, "/vehicle/body?front_wiper=Low"),
        (43, "/vehicle/body?front_wiper=High"),
        (48, "/vehicle/body?front_wiper=Off"),
        (50, "NEXT"),
        (58, "/vehicle/lights?feature=headlamp_mode&value=LowBeam"),
        (72, "/vehicle/controls?brake=85"),
        (75, "/vehicle/controls?brake=0"),
        (79, "REQ:더 차분하게"),
        (89, "/vehicle/controls?brake=30"),
        (92.5, "/vehicle/gear?gear=Park"),
        (93, "/vehicle/controls?brake=0"),
        (94, "STOP"),
        (95, "/vehicle/lights?feature=headlamp_mode&value=Off"),
    ] + steering_ramp(30.5, 140, 2.0, 2.0, 2.0)
    acts.sort(key=lambda a: a[0])

    adb("logcat", "-c")
    h0 = time.time()
    dev_ms = int(adb("shell", "date", "+%s%3N").stdout.strip())
    clock_offset = dev_ms / 1000.0 - (h0 + time.time()) / 2  # device minus host, seconds

    rec = subprocess.Popen([ADB, "shell", "screenrecord", "--time-limit", str(DURATION + 10), "--size", "1920x1080",
                            "--bit-rate", "6000000", "/sdcard/drivejam_demo.mp4"])
    t0 = time.time()
    play_at = None

    stop_odo = threading.Event()

    def odometer_loop():  # integrate the speed profile into the odometer the app reads
        km, last = odometer_now(), time.time()  # the console refuses a decreasing odometer
        while not stop_odo.is_set():
            now = time.time()
            v = speed_at(now - t0)
            km += v * (now - last) / 3600.0
            last = now
            try:
                put(f"/vehicle/odometer?traveled_distance_km={km:.4f}")
                # no /vehicle/controls?speed=…: it re-publishes every control and releases the brake
            except OSError as e:
                print("odometer put failed:", e)
            time.sleep(0.25)

    threading.Thread(target=odometer_loop, daemon=True).start()

    for at, action in acts:
        time.sleep(max(0.0, t0 + at - time.time()))
        if action == "PLAY":
            command("play")
            play_at = time.time() - t0
        elif action == "NEXT":
            command("next")
        elif action.startswith("REQ:"):
            b64 = base64.b64encode(action[4:].encode("utf-8")).decode()
            adb("shell", "am", "start", "-f", "0x30000000", "-n", ACT, "--es", "request_b64", b64)
        elif action == "STOP":
            command("stop")
            stop_at = time.time() - t0
        else:
            put(action)
        print(f"{time.time() - t0:6.1f}s  {action}")

    time.sleep(max(0.0, t0 + DURATION - time.time()))
    stop_odo.set()
    adb("shell", "pkill", "-INT", "screenrecord")
    rec.wait(timeout=30)
    time.sleep(2)

    # 4) collect
    video, wav, final = OUT / "screen.mp4", OUT / "jam.wav", OUT / "drive-jam-demo.mp4"
    adb("pull", "/sdcard/drivejam_demo.mp4", str(video), check=True)
    user = adb("shell", "am", "get-current-user").stdout.strip() or "0"
    with open(wav, "wb") as f:  # internal storage of a debuggable app: readable through run-as
        subprocess.run([ADB, "exec-out", "run-as", PKG, "--user", user, "cat", "files/jam.wav"], stdout=f, check=True)
    if wav.stat().st_size < 1000:
        raise SystemExit("jam.wav is empty: did playback start and stop cleanly?")
    audio_sec = (wav.stat().st_size - 44) / (44100 * 4)
    ratio = audio_sec / (stop_at - (play_at or 6))
    print(f"audio {audio_sec:.1f}s for {stop_at - (play_at or 6):.1f}s of playback -> realtime ratio {ratio:.2f}")
    if ratio < 0.97:
        print("WARNING: the synth fell behind real time (CPU starved); audio will drift and stutter")

    # 5) mux: place the WAV exactly where the engine rendered it and stretch it onto wall-clock time
    import re
    log = adb("logcat", "-d", "-s", "DriveJam:D").stdout
    ws, we = (int(re.search(rf"wav {k} (\d+)", log).group(1)) / 1000.0 for k in ("start", "end"))
    start = ws - clock_offset - t0
    tempo = audio_sec / (we - ws)
    print(f"wav starts at {start:.2f}s, atempo {tempo:.3f}")
    for line in log.splitlines():
        if "hard brake" in line or "brake event" in line or ("speed=" in line and "brake=true" in line):
            print("app:", line.split("DriveJam:", 1)[-1].strip())
    delay_ms = int(max(0.0, start) * 1000)
    for i, (_, _, c) in enumerate(CAPTIONS):
        (OUT / f"cap{i}.txt").write_text(c, encoding="utf-8")
    draw = ",".join(
        f"drawtext=fontfile='{FONT}':textfile=cap{i}.txt:enable='between(t,{a},{b})':x=(w-text_w)/2:y=h-98:"
        f"fontsize=40:fontcolor=white:box=1:boxcolor=black@0.65:boxborderw=18"
        for i, (a, b, _) in enumerate(CAPTIONS)
    )
    subprocess.run([
        "ffmpeg", "-y", "-v", "error", "-i", video.name, "-i", wav.name,
        "-filter_complex", f"[0:v]fps=30,{CROP},{draw}[v];[1:a]atempo={tempo:.4f},adelay={delay_ms}|{delay_ms},apad[a]",
        "-map", "[v]", "-map", "[a]", "-t", str(DURATION), "-c:v", "libx264", "-preset", "medium", "-crf", "20",
        "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", final.name,
    ], check=True, cwd=OUT)
    print("wrote", final)


if __name__ == "__main__":
    main()
