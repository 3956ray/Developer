"""Read-only verification of hardcoded synthetic CP8 SAF snapshots."""
import base64,hashlib,json,pathlib,xml.etree.ElementTree as E
p=pathlib.Path(__file__).parent
def read(name): return json.loads((p/(name+".json")).read_text())
a=read("before-export");b=read("restored-empty");c=read("restored-conflict")
exports=[]
for file in ["exported.thinkbackup.json","default-export.thinkbackup.json"]:
 e=json.loads((p/file).read_text());raw=base64.b64decode(e["payload"],validate=True)
 assert len(raw)==e["payloadBytes"] and hashlib.sha256(raw).hexdigest()==e["payloadSha256"]
 exports.append(e)
assert exports[0]["exportId"]!=exports[1]["exportId"]
assert exports[0]["payload"]==exports[1]["payload"]
for name in ["after-export-cancel","after-prefix-export","after-import-cancel","after-enospc","after-invalid-import","after-source-preview","after-preview-cancel"]:
 assert read(name)==a,name
assert read("empty-before-import")==read("empty-preview")
assert all(not v for v in read("empty-preview").values())
for table in ["notes","drafts","categories"]: assert a[table]==b[table],table
assert a["reminders"][0][:8]==b["reminders"][0][:8]
assert b["reminders"][0][8:]==[0,"DISABLED","",0,"",""]
assert len(b["backup_imports"])==1 and not b["backup_origins"]
assert b["backup_imports"][0][:2]==[exports[0]["exportId"],exports[0]["payloadSha256"]]
for name in ["repeat-unchanged","conflict-preview-unchanged","skip-preview-unchanged"]: assert read(name)==b,name
for table in b:
 if table!="backup_imports":
  for row in b[table]: assert row in c[table],table
assert {k:len(v) for k,v in c.items()}==dict(notes=3,drafts=5,categories=2,reminders=2,backup_imports=2,backup_origins=1)
copy_id,export_id,source_id=c["backup_origins"][0]
assert export_id==exports[1]["exportId"] and source_id=="backup-note" and copy_id!=source_id
for table in ["notes","drafts"]:
 source=next(x for x in b[table] if x[0]==source_id)
 copy=next(x for x in c[table] if x[0]==copy_id)
 assert source[1:]==copy[1:],table
rule=next(x for x in c["reminders"] if x[1]==copy_id)
assert rule[0]!=b["reminders"][0][0] and rule[2:]==b["reminders"][0][2:]
assert read("conflict-repeat-unchanged")==c
counts={x.attrib["name"]:int(x.attrib["value"]) for x in E.parse(p/"enospc-events.xml").getroot()}
assert counts==dict(rename_same_id=1,open=1,ENOSPC=1)
assert "space-fixed" not in (p/"enospc-provider-state.xml").read_text()
print("PASS: exact payload checksum; read-only/cancel/failure; empty restore; disabled rules; new-ID conflict bundle; receipts; actual ENOSPC callback")
