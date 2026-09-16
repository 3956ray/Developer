"""Two previously admitted synthetic phrases for ordinary dictation UI regression, not quality scoring."""
import pathlib,json,subprocess,socket,struct,hashlib
root=pathlib.Path(__file__).parent
legacy=json.loads((root.parents[1]/'voice/fixtures/plan.json').read_text());clips=[];records=[]
try:
 for index in [0,27]:
  case=legacy['cases'][index]
  r=subprocess.run(['/usr/bin/sandbox-exec','-p','(version 1)(allow default)(deny network*)(deny file-write*)','/private/tmp/thinkv2-controls-synthetic'],input=case['text'].encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=130)
  assert r.returncode==0 and 0<len(r.stdout)<=960000
  clips.append(bytearray(r.stdout));records.append({'id':case['id'],'text':case['text'],'samples':len(r.stdout)//2,'pcmSha256':hashlib.sha256(r.stdout).hexdigest(),'voiceMetadata':r.stderr.decode()});del r
 with socket.create_connection(('127.0.0.1',5055),timeout=120) as connection:
  for clip in clips:connection.sendall(struct.pack('!I',len(clip)//2)+clip)
 (root.parent/'ordinary-ui-input-identities.json').write_text(json.dumps({'purpose':'ordinary dictation UI regression only; no V7 rescoring','clips':records},ensure_ascii=False,indent=2)+'\n')
 print('Two ordinary UI regression samples sent in RAM only.')
finally:
 for clip in clips:clip[:]=b'\0'*len(clip)
