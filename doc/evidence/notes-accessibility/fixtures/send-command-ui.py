"""Non-private synthetic input only; PCM never saved, only hashes/text metadata persist."""
import pathlib,json,subprocess,socket,struct,hashlib,platform
root=pathlib.Path(__file__).parent;plan=json.loads((root/'plan.json').read_text());clips=[];identities=[]
try:
 for case in plan['cases']:
  result=subprocess.run(['/usr/bin/sandbox-exec','-p','(version 1)(allow default)(deny network*)(deny file-write*)','/private/tmp/thinkv2-controls-synthetic'],input=case['text'].encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=130)
  assert result.returncode==0 and 0<len(result.stdout)<=960000 and len(result.stdout)%2==0
  assert ('voice='+plan['voice']+' language=zh-CN') in result.stderr.decode()
  pcm=bytearray(result.stdout);clips.append(pcm);identities.append(dict(case,voice=plan['voice'],rate=plan['rate'],samples=len(pcm)//2,pcmSha256=hashlib.sha256(pcm).hexdigest(),host=platform.mac_ver()[0],generatorSha256=hashlib.sha256(pathlib.Path('/private/tmp/thinkv2-controls-synthetic').read_bytes()).hexdigest()))
  del result
 with socket.create_connection(('127.0.0.1',5054),timeout=120) as connection:
  connection.sendall(struct.pack('!I',len(clips)))
  for identity,pcm in zip(identities,clips):
   header=json.dumps(identity,ensure_ascii=False).encode();connection.sendall(struct.pack('!I',len(header))+header+struct.pack('!I',len(pcm)//2));connection.sendall(pcm)
 (root.parent/'real-ui-input-identities.json').write_text(json.dumps(identities,ensure_ascii=False,indent=2)+'\n')
 print('Four bounded-memory synthetic clips sent; text/hash identities saved, no audio files.')
finally:
 for pcm in clips:pcm[:]=b'\0'*len(pcm)
