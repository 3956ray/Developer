import subprocess,pathlib,sys
adb=['/Users/orderly_ray/Library/Android/sdk/platform-tools/adb','-P','5057','-s','127.0.0.1:5597']
out=pathlib.Path('doc/evidence/reminders-usability/regression');out.mkdir(exist_ok=True)
classes=sys.argv[1:] or ['ReminderUiTest','AndroidReminderTest','NotesUiTest','AndroidLifecycleTest','backup.AndroidBackupTest','backup.BackupRestoreStateDeviceTest#actualRestoreButtonDisablesInMemoryAiAndReloadsVocabularyAndRejectsStalePreview']
for name in classes:
 for args in [['shell','settings','put','system','font_scale','1.0'],['shell','cmd','uimode','night','no'],['shell','wm','size','reset'],['shell','pm','clear','com.example.thinkv2']]:subprocess.run(adb+args,check=True,capture_output=True)
 r=subprocess.run(adb+['shell','am','instrument','-w','-e','class','com.example.thinkv2.'+name,'com.example.thinkv2.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True,timeout=240)
 (out/(name+'.log')).write_text(r.stdout+r.stderr);print(name,r.stdout,flush=True)
 if 'OK (' not in r.stdout:raise SystemExit(1)
