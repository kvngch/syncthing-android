#!/usr/bin/env bash
# Smoke test and screenshots on the CI emulator: the debug APK is installed, the onboarding is
# passed, Syncthing must start, then one screenshot per screen.
# Fails if the app crashed or if Syncthing never answered. Usage: captures.sh <directory of the APK>
set -uo pipefail
PAQUET=com.github.catfriend1.syncthingfork.debug
mkdir -p captures
adb install -r "$(ls "$1"/*.apk | head -1)"

# Hide the system "isn't responding" windows, which can cover the app on a slow emulator.
adb shell settings put global hide_error_dialogs 1
# Neutral status bar: fixed clock, full battery, no notification.
adb shell settings put global sysui_demo_allowed 1
adb shell settings put system time_12_24 24
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
demo enter
demo clock -e hhmm 1200
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e mobile hide
demo notifications -e visible false

# The permissions the onboarding asks for, granted up front: only the welcome and key
# generation pages are left.
adb shell appops set $PAQUET MANAGE_EXTERNAL_STORAGE allow
for p in POST_NOTIFICATIONS ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION CAMERA; do
  adb shell pm grant $PAQUET android.permission.$p 2>/dev/null
done
adb shell dumpsys deviceidle whitelist +$PAQUET >/dev/null

photo() { adb exec-out screencap -p > "captures/$1.png"; }

# Centre of the first node whose attribute $2 (text by default) equals $1, case-insensitive.
# Compose and the views both expose their texts to accessibility, thus to uiautomator.
cherche() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/ui.xml > ui.xml
  python3 -c '
import html, re, sys
x = open("ui.xml", encoding="utf-8").read()
for n in re.findall(r"<node [^>]*>", x):
    t = re.search(r" %s=\"([^\"]*)\"" % sys.argv[2], n)
    if t and html.unescape(t.group(1)).lower() == sys.argv[1].lower():
        a, b, c, d = map(int, re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", n).groups())
        print((a + c) // 2, (b + d) // 2)
        break
' "$1" "${2:-text}"
}

# Taps the first node matching $1, by text then by content description.
touche() {
  local xy
  xy=$(cherche "$1" text)
  [ -z "$xy" ] && xy=$(cherche "$1" content-desc)
  [ -z "$xy" ] && { echo "absent : $1"; return 1; }
  adb shell input tap $xy
}

ecran_actif() { adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'; }

# A slow emulator shows "Pixel Launcher isn't responding" over the app, hide_error_dialogs
# notwithstanding: taps would land on that window.
attendre_anr() { touche "Wait" >/dev/null && sleep 2; }

retour() { adb shell input keyevent KEYCODE_BACK; sleep 2; }

# Onboarding: welcome, then key generation, "Continue" then "Finish" until the main screen is up.
adb shell monkey -p $PAQUET -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 10
photo 00-onboarding
for _ in $(seq 1 30); do
  ecran_actif | grep -q MainActivity && break
  attendre_anr
  touche "Continue" >/dev/null || touche "Finish" >/dev/null
  sleep 5
done
ecran_actif | tee captures/ecran-principal.txt

# Syncthing is up once its web GUI answers.
EN_LIGNE=0
for _ in $(seq 1 60); do
  if adb logcat -d | grep -q 'Web GUI has come online'; then EN_LIGNE=1; break; fi
  sleep 3
done
sleep 5

photo 01-dossiers
touche "Devices" && sleep 3 && photo 02-appareils
touche "Status" && sleep 3 && photo 03-statut
# Back to the main screen: the first back may only close the keyboard, and leaving an edit
# screen asks "Discard your changes?".
principal() {
  for _ in 1 2 3 4 5 6; do
    attendre_anr
    ecran_actif | grep -q MainActivity && return 0
    touche "OK" >/dev/null && sleep 2 || retour
  done
  return 1
}

touche "Folders" && sleep 2
if touche "Add Folder"; then
  sleep 4 && photo 04-nouveau-dossier
  principal
fi
touche "Devices" && sleep 2
if touche "Add Device"; then
  sleep 4 && photo 05-nouvel-appareil
  principal
fi

# The navigation drawer, opened by its toolbar button from the main screen.
tiroir() {
  principal && { touche "Open Main Menu" >/dev/null || touche "Main Menu" >/dev/null; } && sleep 3
}
tiroir && photo 06-tiroir
touche "Settings" && sleep 4 && photo 07-reglages
tiroir && touche "Recent changes" && sleep 5 && photo 08-changements-recents
tiroir && touche "Show device ID" && sleep 3 && photo 09-identifiant && retour
tiroir && touche "Web GUI" && sleep 15 && photo 10-interface-web

adb logcat -d -b crash > captures/plantages.txt
adb logcat -d -t 600 '*:W' > captures/journal.txt
adb logcat -d -s SyncthingService:* SyncthingRunnable:* RestApi:* > captures/syncthing.txt

ECHEC=0
if grep -q "$PAQUET" captures/plantages.txt; then
  echo "ECHEC : l'appli a plante, voir captures/plantages.txt"
  ECHEC=1
fi
if [ "$EN_LIGNE" != 1 ]; then
  echo "ECHEC : l'interface web de Syncthing n'a jamais repondu, voir captures/syncthing.txt"
  ECHEC=1
fi
exit $ECHEC
