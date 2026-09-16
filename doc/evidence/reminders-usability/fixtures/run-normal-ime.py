import subprocess,pathlib
adb=['/Users/orderly_ray/Library/Android/sdk/platform-tools/adb','-P','5057','-s','127.0.0.1:5597']
out=pathlib.Path('doc/evidence/reminders-usability/normal-ime');out.mkdir(exist_ok=True)
subprocess.run(adb+['install','-r','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'],check=True,capture_output=True)
subprocess.run(adb+['shell','pm','clear','com.example.thinkv2'],check=True,capture_output=True)
r=subprocess.run(adb+['shell','am','instrument','-w','-e','case','2.0-dark-normal','-e','normalIme','true','-e','class','com.example.thinkv2.ReminderUsabilityTest','com.example.thinkv2.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True,timeout=240)
(out/'instrumentation.log').write_text(r.stdout+r.stderr);print(r.stdout+r.stderr,flush=True)
for f in subprocess.check_output(adb+['shell','run-as','com.example.thinkv2','ls','files'],text=True).split():
 if f.startswith('reminders-'):(out/f).write_bytes(subprocess.check_output(adb+['exec-out','run-as','com.example.thinkv2','cat','files/'+f]))
if 'OK (1 test)' not in r.stdout:raise SystemExit(1)
