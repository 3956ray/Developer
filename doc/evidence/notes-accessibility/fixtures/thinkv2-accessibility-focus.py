import subprocess,pathlib,time,shutil
adb=['/Users/orderly_ray/Library/Android/sdk/platform-tools/adb','-P','5056','-s','127.0.0.1:5595'];out=pathlib.Path('doc/evidence/notes-accessibility/keyboard-final')
if out.exists():shutil.move(str(out),str(out.parent/'attempts'/('keyboard-'+str(time.time_ns()))))
out.mkdir()
for apk in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:subprocess.run(adb+['install','-r',apk],check=True,capture_output=True)
subprocess.run(adb+['shell','pm','clear','com.example.thinkv2'],check=True,capture_output=True)
r=subprocess.run(adb+['shell','am','instrument','-w','-e','class','com.example.thinkv2.NotesFocusTest','com.example.thinkv2.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True,timeout=90);(out/'instrumentation.log').write_text(r.stdout+r.stderr);print(r.stdout)
listing=subprocess.run(adb+['shell','run-as','com.example.thinkv2','ls','files'],capture_output=True,text=True)
for f in listing.stdout.splitlines():
 if f.startswith('accessibility-'):(out/f).write_bytes(subprocess.check_output(adb+['exec-out','run-as','com.example.thinkv2','cat','files/'+f]))
assert 'OK (1 test)' in r.stdout
