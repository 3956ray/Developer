import subprocess,pathlib,time,sys,json
adb=['/Users/orderly_ray/Library/Android/sdk/platform-tools/adb','-P','5056','-s','127.0.0.1:5595'];out=pathlib.Path('doc/evidence/notes-accessibility/matrix');out.mkdir(exist_ok=True)
for apk in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:subprocess.run(adb+['install','-r',apk],check=True,capture_output=True)
cases=sys.argv[1:] or ['1.0-light','1.0-dark','1.5-light','1.5-dark','2.0-light','2.0-dark']
for case in cases:
 font,theme=case.split('-');dest=out/case
 if dest.exists():
  attempts=out.parent/'attempts';attempts.mkdir(exist_ok=True);dest.rename(attempts/(case+'-'+str(time.time_ns())))
 dest.mkdir()
 for args in [['shell','settings','put','system','font_scale',font],['shell','cmd','uimode','night','yes' if theme=='dark' else 'no'],['shell','wm','size','840x1600'],['shell','settings','put','secure','show_ime_with_hard_keyboard','1'],['shell','pm','clear','com.example.thinkv2']]:subprocess.run(adb+args,check=True,capture_output=True)
 r=subprocess.run(adb+['shell','am','instrument','-w','-e','case',case,'-e','class','com.example.thinkv2.NotesAccessibilityTest','com.example.thinkv2.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True,timeout=240)
 old=dest/'instrumentation.log'
 if old.exists():old.rename(dest/('attempt-'+str(time.time_ns())+'.log'))
 old.write_text(r.stdout+r.stderr);print(case,r.stdout,flush=True)
 files=subprocess.check_output(adb+['shell','run-as','com.example.thinkv2','ls','files']).decode().splitlines()
 for f in files:
  if f.startswith('accessibility-'):(dest/f).write_bytes(subprocess.check_output(adb+['exec-out','run-as','com.example.thinkv2','cat','files/'+f]))
 if 'OK (1 test)' not in r.stdout:raise SystemExit(1)
