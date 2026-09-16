# Static ONNX protobuf wire inspection; never import/load/execute the model.
from pathlib import Path
from collections import Counter
import json
p=Path('/private/tmp/thinkv2-voice-002-review');data=memoryview((p/'model.int8.onnx').read_bytes())
def fields(b):
 i=0
 def varint():
  nonlocal i
  n=0
  for shift in range(0,70,7):
   assert i<len(b);x=b[i];i+=1;n|=(x&127)<<shift
   if x<128:return n
  raise ValueError('invalid varint')
 while i<len(b):
  key=varint();f,w=key>>3,key&7;assert f
  if w==0:v=varint()
  elif w in (1,5):size=8 if w==1 else 4;v=b[i:i+size];i+=size
  elif w==2:size=varint();v=b[i:i+size];i+=size;assert len(v)==size
  else:raise ValueError(('unsupported wire',w))
  assert i<=len(b);yield f,w,v
ops=Counter();tensors=0;external=[];graphs=0

def tensor(b):
 global tensors
 tensors+=1
 for f,w,v in fields(b):
  if f==13 or (f==14 and v!=0):external.append(f)
def graph(b):
 global graphs
 graphs+=1;assert graphs<100
 for f,w,v in fields(b):
  if f==5:tensor(v)
  if f==1:
   typ='';domain=''
   for nf,nw,nv in fields(v):
    if nf==4:typ=bytes(nv).decode()
    if nf==7:domain=bytes(nv).decode()
    if nf==5:
     for af,aw,av in fields(nv):
      if af in (5,10):tensor(av)
      if af in (6,11):graph(av)
   ops[domain+'::'+typ]+=1
metadata={};opsets=[]
for f,w,v in fields(data):
 if f==7:graph(v)
 if f==8:opsets.append({str(k):int(x) if ww==0 else bytes(x).decode() for k,ww,x in fields(v)})
 if f==14:
  row={k:bytes(x).decode() for k,ww,x in fields(v)};metadata[row.get(1,'')]=row.get(2,'')
assert not external,external
assert all(k.split('::')[0] in ('','ai.onnx','com.microsoft') for k in ops)
r=dict(bytes=len(data),graphs=graphs,tensors=tensors,externalDataEntries=len(external),operators=dict(ops),opsets=opsets,metadata=metadata,scope='Static protobuf structure only, not ONNX semantic validation or inference safety proof')
(p/'model-structure.json').write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n');print('graphs',graphs,'tensors',tensors,'external',len(external),'operators',len(ops));print('metadata',list(metadata))
