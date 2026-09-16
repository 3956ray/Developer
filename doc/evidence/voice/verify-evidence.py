"""Verify identities and measurements; a complete report may still fail product quality gates."""
import pathlib,json,math,hashlib,re,sys
p=pathlib.Path(__file__).parent
plan=json.loads((p/'fixtures/plan.json').read_text())
raw=json.loads((p/'recognition-results.json').read_text());assert len(raw)==30
assessment=json.loads((p/'recognition-assessment.json').read_text())
correct=total=0
for expected,result,judgment in zip(plan['cases'],raw,assessment['cases']):
 assert result['identity']['id']==expected['id']==judgment['id']
 assert result['identity']['text']==expected['text']==judgment['reference']
 assert result['raw']==judgment['raw'] and result['corrected'] is None
 assert result['identity']['generator']['voice']==plan['voice']
 assert len(result['pcmSha256'])==64 and 0<result['samples']<=1440000
 text=re.sub(r'\s+','',result['raw'])
 correct+=sum(name in text for name in expected['properNames']);total+=len(expected['properNames'])
assert (correct,total)==(assessment['properNameCorrect'],assessment['properNameOccurrences'])
assert sum(x['developerUsabilityPass'] for x in assessment['cases'])==assessment['developerUsabilityPass']
perf=json.loads((p/'performance-results.json').read_text())
short=[r for r in perf if r['identity']['id'].startswith('P30')];long=[r for r in perf if r['identity']['id'].startswith('S90')]
for r in perf:
 seconds=30 if r in short else 90
 assert r['mode']=='realtime-product-capture' and r['sourceClosed'] and r['failure']=='',r['identity']['id']
 assert r['samples']==r['capturedSamples']==seconds*16000
 assert r['wallMs']>=seconds*1000 and r['releaseToResultMs'] is not None and r['releaseToResultMs']>=0
 assert r['raw'].strip() and r['corrected'] is None
if '--partial' not in sys.argv:assert (len(short),len(long))==(10,20),(len(short),len(long))
times=sorted(r['releaseToResultMs'] for r in short);p95=times[math.ceil(.95*len(times))-1] if times else None
summary={'rawNames':f'{correct}/{total}','rawNameAccuracy':correct/total,'nameGatePass':correct/total>=.9,'developerUsability':f"{assessment['developerUsabilityPass']}/30",'usabilityGatePass':assessment['developerUsabilityPass']>=27,'p30Runs':len(short),'p95Ms':p95,'performanceGatePass':len(short)==10 and p95<=5000,'s90Runs':len(long),'stabilityGatePass':len(long)==20,'finalQualityGatePass':False,'scope':'Synthetic fixed voice on isolated emulator. Not Xiaomi microphone/father acceptance.'}
summary['finalQualityGatePass']=all(summary[x] for x in ['nameGatePass','usabilityGatePass','performanceGatePass','stabilityGatePass'])
print(json.dumps(summary,ensure_ascii=False,indent=2))
