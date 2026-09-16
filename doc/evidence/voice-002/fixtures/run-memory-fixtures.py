"""Synthetic-only input. Audio exists only in pipes, RAM and a scoped ADB local socket."""
import subprocess,socket,struct,json,pathlib,hashlib,time,sys,platform
root=pathlib.Path(__file__).parent
plan=json.loads((root.parent.parent/'voice/fixtures/plan.json').read_text())
mode=sys.argv[1]
assert mode=='recognition'
output=root.parent/(mode+'-results.json')
audio={};metadata={}
for case in (plan['cases'] if mode=='recognition' else plan['cases'][:10]):
 r=subprocess.run(['/usr/bin/sandbox-exec','-p','(version 1)(allow default)(deny network*)(deny file-write*)','/private/tmp/thinkv2-synthetic-voice'],input=case['text'].encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=130)
 assert r.returncode==0,(case['id'],r.returncode,r.stderr.decode(errors='replace')[:300])
 assert ('voice='+plan['voice']+' language=zh-CN') in r.stderr.decode(),r.stderr.decode()
 pcm=bytearray(r.stdout);assert 0<len(pcm)<=90*32000 and len(pcm)%2==0
 audio[case['id']]=pcm;metadata[case['id']]={'voice':plan['voice'],'rate':plan['rate'],'host':platform.mac_ver()[0],'generatorSha256':hashlib.sha256(pathlib.Path('/private/tmp/thinkv2-synthetic-voice').read_bytes()).hexdigest(),'seconds':len(pcm)/32000}
 assert sum(map(len,audio.values()))<64*1024*1024
 print('generated',case['id'],len(pcm)/32000,flush=True)
 del r
cases=[]
if mode=='recognition':
 for case in plan['cases']:cases.append((dict(case,mode='throughput',generator=metadata[case['id']]),audio[case['id']]))
else:
 combined=bytearray().join(audio[c['id']] for c in plan['cases'][:10]);assert len(combined)>=30*32000
 thirty=combined[:30*32000]
 ninety=(combined*((90*32000+len(combined)-1)//len(combined)))[:90*32000]
 for i in range(10):cases.append(({'id':f'P30-{i+1:02}','mode':'realtime','source':'CN01-CN10 ordered concatenation, first 30 seconds','voice':plan['voice'],'rate':plan['rate']},thirty))
 for i in range(20):cases.append(({'id':f'S90-{i+1:02}','mode':'realtime','source':'CN01-CN10 ordered concatenation/repetition, first 90 seconds','voice':plan['voice'],'rate':plan['rate']},ninety))
 combined[:]=b'\0'*len(combined)
def recv_exact(s,n):
 result=bytearray()
 while len(result)<n:
  chunk=s.recv(n-len(result));assert chunk,'fixture disconnected';result.extend(chunk)
 return result
results=[]
with socket.create_connection(('127.0.0.1',5051),timeout=180) as s:
 s.sendall(struct.pack('!I',len(cases)))
 for identity,pcm in cases:
  header=json.dumps(identity,ensure_ascii=False).encode();s.sendall(struct.pack('!I',len(header))+header+struct.pack('!I',len(pcm)//2))
  sent=time.monotonic();s.sendall(pcm);last_sent=time.monotonic()
  size=struct.unpack('!I',recv_exact(s,4))[0];assert size<256*1024
  record=json.loads(recv_exact(s,size));ended=time.monotonic()
  assert record['pcmSha256']==hashlib.sha256(pcm).hexdigest()
  record['hostTransferAndResultMs']=round((ended-sent)*1000,3)
  record['hostLastSendToResultMs']=round((ended-last_sent)*1000,3)
  results.append(record);output.write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n')
  print(json.dumps({'id':identity['id'],'raw':record['raw'],'wallMs':record.get('wallMs'),'releaseToResultMs':record.get('releaseToResultMs'),'failure':record.get('failure'),'capturedSamples':record.get('capturedSamples')},ensure_ascii=False),flush=True)
for pcm in audio.values():pcm[:]=b'\0'*len(pcm)
print('COMPLETE',mode,len(results),flush=True)
