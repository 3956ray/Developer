// Test-only lifecycle probes; never import application data.
import {appendFileSync} from 'node:fs';
const [path,mode]=process.argv.slice(2);
if(mode==='bad-ready')console.log('not-json');
if(mode==='early-exit')process.exit(2);
if(mode==='writer'){
 setInterval(()=>appendFileSync(path.replace(/config\.json$/,'lifecycle-write'),'x'),10);
 console.log(JSON.stringify({port:1}));
}else setInterval(()=>{},1000);
