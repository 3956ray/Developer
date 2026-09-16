import subprocess,pathlib,time
adb=['/Users/orderly_ray/Library/Android/sdk/platform-tools/adb','-P','5056','-s','127.0.0.1:5595'];out=pathlib.Path('doc/evidence/notes-accessibility/voice-regression');out.mkdir(exist_ok=True)
cases=[('command','5054','thinkv2-voice-commands-ui','VoiceCommandEngineTest','send-command-ui.py'),('ordinary','5055','thinkv2-voice-ui','VoiceUiTest#realChineseDraftCursorCorrectionCancelAndLateEdit','send-ordinary-ui.py')]
for name,port,socket,test,sender in cases:
 subprocess.run(adb+['shell','pm','clear','com.example.thinkv2'],check=True,capture_output=True)
 subprocess.run(adb+['forward','tcp:'+port,'localabstract:'+socket],check=True,capture_output=True)
 path=out/(name+'.log')
 with path.open('w') as log:
  process=subprocess.Popen(adb+['shell','am','instrument','-w','-e','class','com.example.thinkv2.voice.'+test,'com.example.thinkv2.test/androidx.test.runner.AndroidJUnitRunner'],stdout=log,stderr=subprocess.STDOUT)
  time.sleep(3);r=subprocess.run(['python3','doc/evidence/notes-accessibility/fixtures/'+sender]);process.wait(timeout=150)
 print(name,path.read_text(),flush=True);assert r.returncode==0 and 'OK (1 test)' in path.read_text()
 for f in subprocess.check_output(adb+['shell','run-as','com.example.thinkv2','ls','files']).decode().splitlines():
  if f.startswith('voice-controls-'):(out/f).write_bytes(subprocess.check_output(adb+['exec-out','run-as','com.example.thinkv2','cat','files/'+f]))
 subprocess.run(adb+['forward','--remove','tcp:'+port],check=True,capture_output=True)
