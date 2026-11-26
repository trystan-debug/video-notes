"""Phone test run on GitHub's Android emulator.

Replays: open app -> Record -> start -> stop -> wait -> reopen.
Then opens the app with a saved finished note too. Reports any crash as a build annotation.
"""
import re
import subprocess
import time

PKG = "com.trystan.videonotes"
APK = "app/build/outputs/apk/debug/app-debug.apk"
results = []


def adb(*args, timeout=60):
    try:
        r = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
        return (r.stdout or "") + (r.stderr or "")
    except subprocess.TimeoutExpired:
        return "TIMEOUT"


def note(msg):
    print(msg, flush=True)
    results.append(msg)


last_xml = ""


def find(label, tries=8):
    global last_xml
    for _ in range(tries):
        d = adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        xml = adb("shell", "cat", "/sdcard/ui.xml")
        last_xml = d + "\n" + xml
        for m in re.finditer(r"<node [^>]*>", xml):
            n = m.group(0)
            if f'text="{label}"' in n or f'content-desc="{label}"' in n:
                b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
                if b:
                    x1, y1, x2, y2 = map(int, b.groups())
                    return (x1 + x2) // 2, (y1 + y2) // 2
        time.sleep(1.5)
    return None


def tap(label):
    p = find(label)
    if not p:
        labels = re.findall(r'(?:text|content-desc)="([^"]+)"', last_xml)
        note(f"Could not find '{label}'. On screen: {labels[:40]} | dump: {last_xml[:300]}")
        return False
    adb("shell", "input", "tap", str(p[0]), str(p[1]))
    note(f"Tapped '{label}'")
    return True


def shot(name):
    """Small screenshot, sent back as a build annotation so it can be viewed remotely."""
    try:
        import base64, io
        from PIL import Image
        png = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout
        im = Image.open(io.BytesIO(png)).convert("RGB")
        im.thumbnail((300, 660))
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=45)
        print(f"::notice title=shot {name}::" + base64.b64encode(buf.getvalue()).decode(), flush=True)
    except Exception as e:
        note(f"screenshot {name} failed: {e}")


def tap_xy(x, y, why):
    adb("shell", "input", "tap", str(x), str(y))
    note(f"Tapped {why} at {x},{y}")


def screen_size():
    m = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)


def alive():
    return adb("shell", "pidof", PKG).strip() not in ("", "TIMEOUT")


def launch():
    adb("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity")


def grant():
    for p in ("CAMERA", "RECORD_AUDIO"):
        adb("shell", "pm", "grant", PKG, f"android.permission.{p}")


def esc(s):
    return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


note(adb("install", "-r", "-g", APK, timeout=180).strip()[-200:])
grant()
adb("logcat", "-c")

# Scenario 1: record -> stop -> reopen (what crashed on the phone)
launch()
time.sleep(10)
note(f"Scenario 1 opened, alive={alive()}")
shot("home")
W, H = screen_size()
if not tap("Record"):
    tap_xy(W // 2, int(H * 0.89), "Record (by position)")
time.sleep(6)
shot("record screen")
if not tap("Start recording"):
    tap_xy(W // 2, int(H * 0.87), "record button (by position)")
time.sleep(7)
shot("recording")
if not tap("Stop recording"):
    tap_xy(W // 2, int(H * 0.87), "stop button (by position)")
time.sleep(5)
shot("after stop")
time.sleep(20)
note(f"After stop, alive={alive()}")
shot("after stop +20s")
adb("shell", "am", "force-stop", PKG)
time.sleep(2)
launch()
time.sleep(12)
note(f"Reopen after recording, alive={alive()}")
shot("reopen")
time.sleep(10)
note(f"Reopen +10s, alive={alive()}")

# Scenario 2: a finished note with a transcript and folder
adb("shell", "am", "force-stop", PKG)
seed = (
    '[{"id":"s2","name":"VideoNote_seed","videoUri":"content://media/external/video/media/999999",'
    '"createdAt":1764000000000,"durationMs":65000,"title":"Sponsors for the next event",'
    '"folder":"Sponsors & Event","status":"done","segments":[{"t":0,"x":"We need sponsors for the next event"},'
    '{"t":4000,"x":"Call the venue about pricing"}],"approxTimes":true,"keywords":["sponsors","event","venue"]}]'
)
existing = adb("shell", "run-as", PKG, "cat", "files/notes.json")
note("Saved notes after scenario 1: " + existing[:1500])
adb("shell", f"echo '{seed}' > /data/local/tmp/seed.json")
adb("shell", f"cat /data/local/tmp/seed.json | run-as {PKG} sh -c 'cat > files/notes.json'")
launch()
time.sleep(8)
note(f"Scenario 2 (finished note) alive={alive()}")
if tap("Sponsors for the next event"):
    time.sleep(5)
    note(f"Opened note detail, alive={alive()}")

# Collect evidence
crash_file = adb("shell", "run-as", PKG, "cat", "files/crash.txt")
log = adb("logcat", "-d", "-v", "brief", timeout=120)
fatal = []
lines = log.splitlines()
for i, line in enumerate(lines):
    if "FATAL EXCEPTION" in line or ("Fatal signal" in line) or ("ANR in " + PKG) in line:
        fatal.append("\n".join(lines[i:i + 45]))
app_lines = [l for l in lines if PKG in l or "VideoNotes" in l or "CameraX" in l or "Recorder" in l][-60:]

print("::notice title=Smoke results::" + esc("\n".join(results)))
if "No such file" not in crash_file and crash_file.strip():
    print("::error title=App crash report::" + esc(crash_file[:6000]))
for f in fatal[:3]:
    print("::error title=Crash in logcat::" + esc(f[:6000]))
if not fatal:
    print("::notice title=No crash seen::" + esc("\n".join(app_lines)[-6000:]))
