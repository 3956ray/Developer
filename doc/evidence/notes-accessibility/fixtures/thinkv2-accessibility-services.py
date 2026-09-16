import subprocess,pathlib,time
adb=['/Users/orderly_ray/Library/Android/sdk/platform-tools/adb','-P','5056','-s','127.0.0.1:5595'];out=pathlib.Path('doc/evidence/notes-accessibility/services');out.mkdir(exist_ok=True)
for apk in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:subprocess.run(adb+['install','-r',apk],check=True,capture_output=True)
for args in [['shell','settings','put','system','font_scale','1.0'],['shell','cmd','uimode','night','no'],['shell','wm','size','reset']]+[['shell','settings','put','global',k,'0'] for k in ['window_animation_scale','transition_animation_scale','animator_duration_scale']]:subprocess.run(adb+args,check=True,capture_output=True)
for test in ['NotesFocusTest','TalkBackServiceTest']:
 subprocess.run(adb+['shell','pm','clear','com.example.thinkv2'],check=True,capture_output=True)
 r=subprocess.run(adb+['shell','am','instrument','-w','-e','class','com.example.thinkv2.'+test,'com.example.thinkv2.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True,timeout=150)
 (out/(test+'.log')).write_text(r.stdout+r.stderr);print(test,r.stdout,flush=True)
 files=subprocess.check_output(adb+['shell','run-as','com.example.thinkv2','ls','files']).decode().splitlines()
 for f in files:
  if f.startswith('accessibility-'):(out/f).write_bytes(subprocess.check_output(adb+['exec-out','run-as','com.example.thinkv2','cat','files/'+f]))
