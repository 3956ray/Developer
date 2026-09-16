"""Read-only verification of the single recorded batch; never regenerates speech or runs a model."""
import hashlib,json,pathlib
root=pathlib.Path(__file__).parent
planpath=root.parent/'voice/fixtures/plan.json'
assert hashlib.sha256(planpath.read_bytes()).hexdigest()=='95d97b465918f40831658cbcbc29390cc2b417be6a13adf542826c76d9db0b6b'
assert hashlib.sha256((root/'decoding-config.json').read_bytes()).hexdigest()=='dc81492e82016c969329b7db8bc883639a97186012d50f1c7e4a6b5780bfca40'
plan=json.loads(planpath.read_text());rows=json.loads((root/'recognition-results.json').read_text());device=json.loads((root/'recognition-device-results.json').read_text());old=json.loads((root.parent/'voice/recognition-results.json').read_text());assessment=json.loads((root/'recognition-assessment.json').read_text())
assert len(rows)==len(device)==len(old)==len(assessment['cases'])==len(plan['cases'])==30
correct=total=usable=0;mismatch=[]
for c,r,d,o,a in zip(plan['cases'],rows,device,old,assessment['cases']):
 assert c['id']==r['identity']['id']==d['identity']['id']==o['identity']['id']==a['id']
 assert c['text']==r['identity']['text']==a['reference']
 for k in ['raw','pcmSha256','samples','identity']:assert r[k]==d[k]
 assert r['raw']==a['raw'] and r['corrected'] is None
 gen=r['identity']['generator'];assert gen['voice']==plan['voice'] and gen['rate']==plan['rate']
 assert gen==dict(o['identity']['generator'],seconds=gen['seconds'])
 hits=[n for n in c['properNames'] if n in r['raw']];assert hits==a['properNameCorrect']
 correct+=len(hits);total+=len(c['properNames']);usable+=a['developerUsabilityPass']
 if r['pcmSha256']!=o['pcmSha256']:mismatch.append(c['id'])
assert (correct,total,usable)==(61,66,23) and mismatch==['CN14']
assert rows[13]['samples']*2==old[13]['samples']
print(json.dumps({'properNames':f'{correct}/{total}','rawNameAccuracy':correct/total,'nameGatePass':True,'strictDeveloperUsability':f'{usable}/30','usabilityGatePass':False,'optimisticUsabilityUpperBound':25,'identicalPcm':29,'differentPcm':mismatch,'longTestsRun':False,'qualityGatePass':False,'privateEmbedding':'PAUSE'},indent=2))
