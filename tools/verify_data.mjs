import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';

// Git on Windows may check out text files with CRLF; compare the original LF content.
const raw = readFileSync(new URL('../app/src/main/assets/hardness_conversion.tsv', import.meta.url), 'utf8').replace(/\r\n/g, '\n');
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
assert.equal(sha(raw), 'c1cf5771d41bfd50b80741b7af20436dbefedf9e615a00c2e5a2ddbc95388324');
const lines = raw.split('\n').filter(Boolean);
const rows = lines.map(s => s.split('\t'));
assert.equal(rows.length, 1564);
assert(rows.every(r => r.length === 5));
assert.equal(new Set(rows.map(r => r.slice(0,2).join('\t'))).size, 9);
assert.equal(rows.filter(r => Number(r[2])>70).length, 688);
assert.equal(rows.length-new Set(lines).size,1);
const rtm = rows.filter(r => r[0]==='РТМ 3-1947-91' && r[1]==='Сталь');
assert.equal(rtm.length,92);
assert.equal(sha(rtm.map(r=>r.join('\t')).join('\n')), '53d145a0c1270c2b0436a885f8500e7383fa9af815e310f491db78c8306a6148');
const excludedHv = new Set([25.5,52.5,54,54.5]);
const excludedHrc = new Set([35,51.5]);
const corrected = rtm.map((r,i) => {
  const legacy=Number(r[2]); assert.equal(legacy,20+i*.5);
  return {hrc:excludedHrc.has(legacy)?null:legacy-(legacy<=34.5?2:legacy<=51?1.5:1),
          hb:Number(r[3]),hv:excludedHv.has(legacy)||!r[4]?null:legacy===26?242:legacy===26.5?245:Number(r[4])};
});
assert.equal(corrected.filter(r=>r.hrc!==null).length,90);
assert.equal(corrected.filter(r=>r.hv!==null).length,84);
assert.deepEqual(corrected.find(r=>r.hrc===43.5),{hrc:43.5,hb:406,hv:439});
for(const key of ['hrc','hb','hv']) {
  const values=corrected.map(r=>r[key]).filter(v=>v!==null);
  assert(values.every((v,i)=>i===0||v>=values[i-1]));
}
const specs = [
  ['ISO 18265:2013','Низколегированные стали',97,'4a8db6a0a2767c7c66a77fa5f29ee8fbb59887c69257875ffd9d2d7256f9570d'],
  ['ISO 18265:2013','Закалённые и отпущенные стали',45,'27ca5285f604f7c16a6f1fa1473e1dc740f52f140c1bd08aa0765c20027f2ace'],
  ['ISO 18265:2013','Стали холодной обработки',64,'e4caccfc6dd17d7701f5268d6aaac5d3758b08267cf8fe5cc1bf5663273ec064'],
  ['ISO 18265:2013','Никелевые стали',48,'a73856792167a9de424f9501e9aa70086a5117439966d6f2cb3c0b40653b595e'],
  ['ASTM E140','Неаустенитные стали',49,'063f3541e84315d4fe9b787f439bbac1fea9e08d16c0516a902e194a64fc56bb'],
  ['ASTM E140','Никелевые стали',48,'697ab4e44a1acb90578420963cf7a36743df37e957ba5c562c106c8a5e936e7d'],
  ['ASTM E140','Медь',43,'21a0807973e95d40cc6f7aed79cf92c3f91fb433e744f412ed4fce831e4c1b08'],
  ['ASTM E140','Деформируемый алюминий',25,'f2bca7c7177b1d2529aad956087ea0bd557f40b5a256717ba48e45f7dddd51ba']
];
for (const [doc,material,count,digest] of specs) {
  const group=rows.filter(r=>r[0]===doc&&r[1]===material).slice(0,count);
  assert.equal(group.length,count);
  assert.equal(sha(group.map(r=>r.join('\t')).join('\n')),digest);
  assert(group.every(r=>!r[2]||(+r[2]>0&&+r[2]<=70)));
  for(const i of [2,3,4]){
    const values=group.filter(r=>r[i]).map(r=>i===3&&material==='Медь'&&+r[4]===110?100:+r[i]);
    assert(values.every((v,j)=>j===0||v>=values[j-1]));
  }
}
const vickers=(a,b,f,mode,n)=>{const d=(a+b)/2*[1,1/1000,1/2000,.3/1000][mode];return (n?.1891:1.854)*f/d**2;};
const brinell=(D,a,b,f,n,scale=1)=>{const d=(a+b)/2*scale;return (n?.102:1)*2*f/(3.14159*D*(D-Math.sqrt(D*D-d*d)));};
const vTests=[
  [[.14,.14,196.1,0,true],1891.9647959183667],[[210,210,49.03,1,true],210.23975056689346],
  [[240,240,98.07,2,true],1287.8497916666665],[[320,340,1.961,3,true],37.83543515967758],
  [[.14,.14,1,0,false],94.59183673469387],[[210,210,5,1,false],210.2040816326531],
  [[271,267,5,2,false],512.4307292602368],[[99,96,.2,3,false],433.3990795529257]
];
const bTests=[[[5,2.2,2.2,1226,true],31.21917833513282],[[2.5,.78,.76,62.5,false],130.95507121966898],[[2.5,77,79,62.5,false,.01],127.53347636322259]];
for(const [args,expected] of vTests)assert(Math.abs(vickers(...args)-expected)<1e-8);
for(const [args,expected] of bTests)assert(Math.abs(brinell(...args)-expected)<1e-8);
assert(Math.abs(vickers(.5,.5,30,0,false)-vickers(.5,.5,30*9.80665,0,true))<.1);
assert(Math.abs(brinell(10,4,4,3000,false)-brinell(10,4,4,3000*9.80665,true))<.1);
console.log('PASS: immutable TSV, 9 validated prefixes, RTM 90 HRC/84 HV, monotonicity, 11 cached Excel examples, N/kgf. Independent JS checks.');
