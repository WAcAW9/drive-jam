"""Record the Drive JAM demo video, fully scripted.

Drives the Pleos Connect Virtual Console REST API on a timeline, records the whole emulator screen,
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
DURATION = 104
# Whole screen (cluster, home media card, taskbar and the app), scaled down to leave a caption strip at the bottom.
FRAME = "scale=1728:972,pad=1920:1080:96:0:color=0x111111"

# (start, end, caption) — seconds from recording start
CAPTIONS = [
    (0, 5, "출발 전 · 누구와 '친구들', 기분 '피곤해'"),
    (5, 10, "잼 시작 · 차가 지금 이 드라이브에 맞는 곡을 고릅니다"),
    (10, 16, "말하듯 요청 · '여름 바다 시티팝으로' → 장르가 바뀝니다"),
    (16, 22, "출발 · 차속에 따라 템포가 움직이고, 가사 없는 백코러스가 얹힙니다"),
    (22, 28, "차선 변경 · 깜빡이 = 하이햇, 핸들 = 필터와 소리 방향"),
    (28, 36, "비 · 와이퍼 Low → High = 빗소리가 촘촘해집니다"),
    (36, 42, "마음에 안 들면 '다음 곡' · 홈 화면 미디어 카드와 스티어링 휠 버튼도 됩니다"),
    (42, 49, "터널 · 낮에 전조등이 켜지면 잔향이 길어집니다"),
    (49, 57, "해 질 녘 · 노을 무렵엔 브라스가 얹힙니다 (시각은 연출)"),
    (57, 61, "급정거 · 곡이 '멈칫'했다가 이어집니다"),
    (61, 76, "정체 · 막히면 드럼을 빼고 허밍만 (판정 시간은 시연용으로 단축)"),
    (76, 84, "길이 뚫리면 · 리듬이 다시 들어옵니다"),
    (84, 92, "도착 · 감속하고 기어 P"),
    (92, 100, "오늘의 드라이브가 한 곡으로 · 드라이버 카드와 도감 스탬프"),
    (100, 104, "Drive JAM — 내 운전이 그대로 음악이 됩니다"),
]

# speed keyframes (t, km/h); linear in between
SPEED = [(0, 0), (16, 0), (22, 60), (52, 60), (56, 85), (57, 85), (59.5, 10), (64, 6), (68, 12), (72, 6), (76, 8),
         (79, 50), (85, 50), (91, 0), (104, 0)]

# brake / steering as last set by the timeline: every controls PUT carries all three, or the console resets the others
CONTROLS = {"brake": 0, "steering": 0}


def odometer_now():
    import json
    with urllib.request.urlopen(CONSOLE + "/vehicle/state", timeout=5) as r:
        return float(json.load(r)["odometer"]["traveled_distance_km"])


def put(path):
    req = urllib.request.Request(CONSOLE + path, method="PUT")
    urllib.request.urlopen(req, timeout=5).read()


def put_controls(speed, **changed):
    CONTROLS.update(changed)
    acc = 4 if speed > 0 and CONTROLS["brake"] == 0 else 0  # a touch of throttle, or the console's physics coasts the cluster down
    put(f"/vehicle/controls?speed={speed:.0f}&accelerator={acc}&brake={CONTROLS['brake']}&steering={CONTROLS['steering']}")


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
        acts.append((t0 + up * i / n, f"STEER:{peak * i / n:.0f}"))
    for i in range(1, n + 1):
        acts.append((t0 + up + hold + down * i / n, f"STEER:{peak * (1 - i / n):.0f}"))
    return acts


def intent(*extras):
    adb("shell", "am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
        "-f", "0x10200000", "-n", ACT, *extras)


def command(extra, *more):
    """Deliver a command to the running activity (singleTop -> onNewIntent)."""
    adb("shell", "am", "start", "-f", "0x30000000", "-n", ACT, "--ez", extra, "true", *more)


def bring_app_to_front():
    for _ in range(4):
        if "drivejam" in adb("shell", "dumpsys", "window").stdout.split("mCurrentFocus", 1)[-1][:200]:
            return
        adb("shell", "input", "tap", *map(str, TASKBAR_ICON))
        time.sleep(2.5)
    raise SystemExit("could not bring Drive JAM to the front")


def main():
    OUT.mkdir(exist_ok=True)
    try:
        put("/vehicle/gear?gear=Park")
    except OSError as e:  # 503 emulator_inactive: the Virtual Console page must be open in a browser
        raise SystemExit(f"Virtual Console not reachable ({e}). Open http://127.0.0.1:8088/ in a browser first.")
    # 1) reset the car
    for p in ["/vehicle/gear?gear=Park", "/vehicle/turn-signal?mode=Off", "/vehicle/body?front_wiper=Off",
              "/vehicle/lights?feature=headlamp_mode&value=Off"]:
        put(p)
    put_controls(0, brake=0, steering=0)

    # 2) app back to "before the first drive". No force-stop: the home media card stays bound to the live session.
    user = adb("shell", "am", "get-current-user").stdout.strip() or "0"
    intent()
    time.sleep(3)
    adb("shell", "input", "keyevent", "111")  # ESC: close any system popup (e.g. the profile menu)
    adb("shell", "input", "swipe", "150", "1190", "750", "1190", "300")  # left panel widget -> its first page (media card)
    bring_app_to_front()
    for _ in range(4):  # the car may auto-resume its last media source after boot: make sure we start silent
        command("demo", "--ez", "reset", "true", "--es", "company", "FRIENDS", "--es", "feeling", "TIRED", "--ef", "hour", "14.0")
        time.sleep(3)
        if "state=PLAYING" not in adb("shell", "dumpsys", "media_session").stdout.split("package=" + PKG, 1)[-1][:600]:
            break

    # 3) timeline
    acts = [
        (5, "PLAY"),
        (10, "REQ:여름 바다 시티팝으로"),
        (16, "/vehicle/gear?gear=Drive"),
        (22, "/vehicle/turn-signal?mode=BlinkLeft"),
        (27, "/vehicle/turn-signal?mode=Off"),
        (28, "/vehicle/body?front_wiper=Low"),
        (31, "/vehicle/body?front_wiper=High"),
        (34, "/vehicle/body?front_wiper=Off"),
        (36, "NEXT"),
        (42, "/vehicle/lights?feature=headlamp_mode&value=LowBeam"),  # daytime + low beam = tunnel
        (48.5, "/vehicle/lights?feature=headlamp_mode&value=Off"),
        (49, "HOUR:17.0"),  # Nov sunset ~17:24 -> the sunset window
        (57, "BRAKE:85"), (59.5, "BRAKE:0"),
        (61, "BRAKE:40"), (62, "BRAKE:0"), (64, "BRAKE:40"), (65, "BRAKE:0"),  # stop-and-go
        (67, "BRAKE:40"), (68, "BRAKE:0"), (70, "BRAKE:40"), (71, "BRAKE:0"), (73, "BRAKE:40"), (74, "BRAKE:0"),
        (89, "BRAKE:30"),
        (92, "/vehicle/gear?gear=Park"),
        (92.5, "BRAKE:0"),
        (100, "STOP"),
    ] + steering_ramp(22.5, 140, 1.5, 1.5, 1.5)
    acts.sort(key=lambda a: a[0])

    adb("logcat", "-G", "16M")  # the system logs a lot; keep the "wav start/end" markers until we read them
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
                if CONTROLS["brake"] == 0:  # while braking, let the console's own physics slow the cluster down
                    put_controls(v)  # the cluster speedometer shows the same speed the app computes
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
        elif action.startswith("BRAKE:"):
            put_controls(speed_at(time.time() - t0), brake=int(action[6:]))
        elif action.startswith("STEER:"):
            put_controls(speed_at(time.time() - t0), steering=int(action[6:]))
        elif action.startswith("HOUR:"):
            command("noop", "--ef", "hour", action[5:])
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
        f"drawtext=fontfile='{FONT}':textfile=cap{i}.txt:enable='between(t,{a},{b})':x=(w-text_w)/2:y=h-72:"
        f"fontsize=38:fontcolor=white:box=1:boxcolor=black@0.0:boxborderw=12"
        for i, (a, b, _) in enumerate(CAPTIONS)
    )
    subprocess.run([
        "ffmpeg", "-y", "-v", "error", "-i", video.name, "-i", wav.name,
        "-filter_complex", f"[0:v]fps=30,{FRAME},{draw}[v];[1:a]atempo={tempo:.4f},adelay={delay_ms}|{delay_ms},apad[a]",
        "-map", "[v]", "-map", "[a]", "-t", str(DURATION), "-c:v", "libx264", "-preset", "medium", "-crf", "20",
        "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", final.name,
    ], check=True, cwd=OUT)
    print("wrote", final)


if __name__ == "__main__":
    main()
