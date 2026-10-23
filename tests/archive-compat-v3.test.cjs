const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');

function fixture() {
  const events = [];
  const journal = [];
  let sheetWrites = 0;
  const context = vm.createContext({
    console, Date, Map, Set,
    LockService: {getScriptLock: () => ({tryLock: () => true, releaseLock() {}})},
    PropertiesService: {getScriptProperties: () => ({getProperty: key =>
      key === 'API_TOKEN' ? 'test-token' : null})}
  });
  vm.runInContext(fs.readFileSync('apps-script/Code.gs', 'utf8'), context);
  const book = {getId: () => 'book'};
  context.getSpreadsheet_ = () => book;
  context.ensureSheet_ = () => ({appendRow: row => journal.push(row)});
  context.constantTimeTokenEquals_ = (a,b) => a === b;
  context.json_ = value => value;
  context.importHealthArchiveFilesV4_ = (_ss, payload) => {
    assert.equal(payload.days.length, 0);
    events.push({target:'drive', payload: JSON.parse(JSON.stringify(payload))});
    return {
      ok:true, archived:true, archiveSchemaVersion:4,
      workouts: payload.workouts.length, sleepSessions:payload.sleepSessions.length,
      measurements: payload.measurements.length
    };
  };
  context.importHealthPayloadV3_ = (_ss,_log,payload) => {
    sheetWrites++;
    events.push({target:'sheets',payload:JSON.parse(JSON.stringify(payload))});
    return {ok:true,schemaVersion:3,days:payload.days.length,
      workouts:payload.workouts.length,sleepSessions:payload.sleepSessions.length,
      measurements:payload.measurements.length};
  };
  const send = body => context.doPost({postData: {contents:JSON.stringify({
    action:'healthSyncV3',token:'test-token',schemaVersion:3,
    days:[],workouts:[],sleepSessions:[],measurements:[],...body
  })}});
  return {context,events,journal,send,getSheetWrites:()=>sheetWrites};
}

test('legacy V3 sample batches go into Drive, not the overloaded Sheets grid',()=>{
  const f=fixture();
  const batch=Array.from({length:2000},(_,i)=>({id:'r'+i,type:'HeartRate',value:60+i%50,
    time:'2026-10-22T08:00:00Z',sourcePackage:'example.source'}));
  const result=f.send({rangeStart:'2026-10-22',rangeEnd:'2026-10-22',measurements:batch});
  assert.equal(result.ok,true);assert.equal(result.archived,true);
  assert.equal(result.archiveSchemaVersion,4);assert.equal(result.measurements,2000);
  assert.equal(result.measurementsStored,2000);assert.equal(result.measurementsSkipped,0);
  assert.equal(f.getSheetWrites(),0);assert.equal(f.events.length,1);
  assert.equal(f.events[0].target,'drive');assert.equal(f.events[0].payload.measurements.length,2000);
  assert.equal(f.journal.length,1);
  assert.equal(f.journal[0][6],'ARCHIVED');
});

test('summary-only V3 remains independent of Drive permissions',()=>{
  const f=fixture();
  f.context.importHealthArchiveFilesV4_=()=>{throw Error('Drive denied');};
  const result=f.send({days:[{date:'2026-10-22',steps:9000}],dayComplete:false});
  assert.equal(result.ok,true);assert.equal(result.days,1);
  assert.equal(f.getSheetWrites(),1);assert.equal(f.events[0].target,'sheets');
});

test('combined V3 summary/sessions are both archived and still update legacy views',()=>{
  const f=fixture();
  const result=f.send({
    days:[{date:'2026-10-22',steps:10000}],
    workouts:[{id:'workout-1',start:'2026-10-22T15:00:00Z'}],
    sleepSessions:[{id:'sleep-1',start:'2026-10-21T23:00:00Z'}],
    measurements:[{id:'sample-1',type:'HeartRate',value:72}],
    dayComplete:false
  });
  assert.equal(result.ok,true);
  assert.equal(result.workouts,1);assert.equal(result.sleepSessions,1);
  assert.equal(result.measurements,1);assert.equal(result.measurementsStored,1);
  assert.equal(f.events.length,2);
  // The day is written first; Drive second (a Drive failure must not lose the summary).
  assert.equal(f.events[0].target,'sheets');assert.equal(f.events[0].payload.measurements.length,0);
  assert.equal(f.events[0].payload.days.length,1);
  assert.equal(f.events[0].payload.workouts.length,1);
  assert.equal(f.events[1].target,'drive');assert.equal(f.events[1].payload.measurements.length,1);
});

test('combined V3 packet keeps the day summary when Drive fails',()=>{
  const f=fixture();
  f.context.importHealthArchiveFilesV4_=()=>{throw Error('Drive quota exceeded');};
  const result=f.send({
    days:[{date:'2026-10-23',steps:15000}],
    sleepSessions:[{id:'sleep-1',start:'2026-10-22T23:00:00Z'}],
    measurements:[{id:'sample-1',type:'HeartRate',value:72}],
    dayComplete:false
  });
  assert.equal(result.ok,true);
  assert.equal(result.days,1);
  assert.equal(result.archived,false);
  assert.equal(result.measurementsSkipped,1);
  assert.match(result.warning,/Drive quota exceeded/);
  assert.equal(f.getSheetWrites(),1);
});

test('Drive failure is never returned as a successful sample upload',()=>{
  const f=fixture();
  f.context.importHealthArchiveFilesV4_=()=>{throw Error('Drive write/readback failed');};
  const result=f.send({measurements:[{id:'sample-1',type:'HeartRate',value:76}]});
  assert.equal(result.ok,false);
  assert.match(result.message,/Drive write\/readback failed/);
  assert.equal(f.getSheetWrites(),0);
  assert.equal(f.journal[f.journal.length-1][6],'ERROR');
});

test('daily sync plan enables full V3 raw upload only when Drive is authorized',()=>{
  const code=fs.readFileSync('apps-script/Code.gs','utf8');
  assert.match(code,/storesHighVolumeRaw: rawFilesReadyV4_\(\)\.ready/);
  assert.match(code,/rawArchiveMode: 'files-v4'/);
  const client=fs.readFileSync('app/src/main/java/ru/doronin/healthconnector/HealthSyncStreamer.kt','utf8');
  assert.match(client,/sendHighVolumeRaw = plan\.storesHighVolumeRaw/);
  assert.match(client,/put\("action", "healthSyncV3"\)/);
});
