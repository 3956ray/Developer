import pathlib,json,subprocess,socket,struct,hashlib
p=pathlib.Path(__file__).parent.parent;plan=json.loads((p/'fixtures/plan.json').read_text());clips=[];records=[]
for index in [0,27]:
 case=plan['cases'][index]
 r=subprocess.run(['/usr/bin/sandbox-exec','-p','(version 1)(allow default)(deny network*)(deny file-write*)','/private/tmp/thinkv2-synthetic-voice'],input=case['text'].encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=130)
 assert r.returncode==0 and 0<len(r.stdout)<=960000
 clips.append(bytearray(r.stdout));records.append({'id':case['id'],'text':case['text'],'samples':len(r.stdout)//2,'pcmSha256':hashlib.sha256(r.stdout).hexdigest(),'voiceMetadata':r.stderr.decode()})
with socket.create_connection(('127.0.0.1',5052),timeout=30) as s:
 for clip in clips:s.sendall(struct.pack('!I',len(clip)//2)+clip)
for clip in clips:clip[:]=b'\0'*len(clip)
(p/'ui-input-identity.json').write_text(json.dumps({'purpose':'UI integration only; excluded from accuracy score','clips':records},ensure_ascii=False,indent=2)+'\n')
print('Two distinct synthetic clips sent in RAM')
