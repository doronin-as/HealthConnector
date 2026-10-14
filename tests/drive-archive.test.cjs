const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const crypto = require('node:crypto');
const zlib = require('node:zlib');
function fixture() {
  const files=[]; let failRead=false;
  const props=new Map([['DRIVE_ARCHIVE_FOLDER_V1','folder'],['DRIVE_ARCHIVE_ENABLED_V1','1']]);
  const blob = bytes => ({bytes:Buffer.from(bytes),getDataAsString:()=>Buffer.from(bytes).toString()});
  const folder={getUrl:()=>"https://drive.google.com/drive/folders/folder",getFilesByName(name){const matches=files.filter(f=>f.name===name);let i=0;return {hasNext:()=>i<matches.length,next:()=>matches[i++]};},createFile(b){const f={name:b.name,getId:()=>String(files.indexOf(f)),getBlob:()=>{if(failRead)throw Error('read failed');return b;}};files.push(f);return f;}};
  const context=vm.createContext({console,Date,Map,Set,PropertiesService:{getScriptProperties:()=>({getProperty:k=>props.get(k),setProperty:(k,v)=>props.set(k,v)})},DriveApp:{getFolderById:()=>folder},Utilities:{DigestAlgorithm:{SHA_256:'sha256'},Charset:{UTF_8:'utf8'},computeDigest:(_,t)=>Array.from(crypto.createHash('sha256').update(t).digest()),newBlob:t=>blob(t),gzip:(b,name)=>Object.assign(blob(zlib.gzipSync(b.bytes)),{name}),ungzip:b=>blob(zlib.gunzipSync(b.bytes)),formatDate:(date,tz)=>new Intl.DateTimeFormat('en-CA',{timeZone:tz,year:'numeric',month:'2-digit',day:'2-digit'}).format(date)}});
  vm.runInContext(fs.readFileSync('apps-script/Code.gs','utf8'),context);
  const spreadsheet={getId:()=> 'book',getSpreadsheetTimeZone:()=> 'Europe/Moscow'};
  return {context,spreadsheet,files,props,failRead:()=>{failRead=true;}};
}
test('repeat upload reuses verified archive, exact zero/null values survive gzip',()=>{const f=fixture();const rows=[['one','2026-10-13T22:00:00Z',0,null]];for(let i=0;i<2;i++)f.context.archiveRowsToDriveV1_(f.spreadsheet,'raw',['Id','Time','Value','Min'],rows,'upsert');assert.equal(f.files.length,1);const d=JSON.parse(zlib.gunzipSync(f.files[0].getBlob().bytes));assert.equal(d.day,'2026-10-14');assert.deepEqual(d.rows,rows);});
test('unbounded day is split without dropping rows',()=>{const f=fixture();const rows=Array.from({length:4501},(_,i)=>[String(i),'2026-10-14']);f.context.archiveRowsToDriveV1_(f.spreadsheet,'raw',['Id','Date'],rows,'upsert');assert.equal(f.files.length,3);assert.equal(f.files.reduce((n,file)=>n+JSON.parse(zlib.gunzipSync(file.getBlob().bytes)).rows.length,0),4501);});
test('unknown dates retained and readback failure propagates',()=>{const f=fixture();f.failRead();assert.throws(()=>f.context.archiveRowsToDriveV1_(f.spreadsheet,'raw',['Id'],[['x']],'upsert'),/read failed/);assert.match(f.files[0].name,/^undated__/);});
test('raw sheet adapter never opens or creates a grid',()=>{const f=fixture();const sheet=f.context.ensureSheet_(f.spreadsheet,'HC_Измерения',['Id','Time']);f.context.upsertByKey_(sheet,[['x','2026-10-14']],1);assert.equal(f.files.length,1);});
test('V3 archive failure cannot mark the day complete; raw HF samples retained',()=>{const f=fixture();let committed=false;f.props.set('MEASUREMENT_MIGRATED_5M_V1','1');f.context.ensureSheet_=(_,name,headers)=>({name,headers});f.context.assertMeasurementsWritableV1_=()=>{};f.context.upsertDayObjectsPartialV3_=()=>{committed=true;};f.context.upsertByKey_=(sheet,rows)=>{if(sheet.name==='HC_Измерения'){assert.equal(rows.length,1);throw Error('Drive unavailable');}};assert.throws(()=>f.context.importHealthPayloadV3_(f.spreadsheet,{appendRow(){}},{days:[{date:'2026-10-14'}],workouts:[],measurements:[{id:'r|1',time:'2026-10-14',type:'HeartRate',value:60}],dayComplete:true}),/Drive unavailable/);assert.equal(committed,false);});
function migrationFixture() {
 const f=fixture(); let rows=[['Id','Time'],['a','2026-10-14'],['b','2026-10-14']];let deletions=0;
 const sheet={getName:()=> 'HC_Измерения',getLastRow:()=>rows.length,getLastColumn:()=>2,getFrozenRows:()=>1,getMaxRows:()=>10,getRange:(start,col,count,width)=>({getValues:()=>rows.slice(start-1,start-1+count),getFormulas:()=>Array.from({length:count},()=>Array(width).fill(''))}),deleteRows:(start,count)=>{rows.splice(start-1,count);deletions++;},hideSheet(){}};
 Object.assign(f.spreadsheet,{getSheetByName:name=>name==='HC_Измерения'?sheet:null});
 f.context.getSpreadsheet_=()=>f.spreadsheet;f.context.archiveDependentSheetsV1_=()=>[];
 f.context.LockService={getScriptLock:()=>({tryLock:()=>true,releaseLock(){}})};f.context.ScriptApp={getProjectTriggers:()=>[]};
 return {...f,rows:()=>rows,deletions:()=>deletions};
}
test('migration readback failure never deletes original records',()=>{const f=migrationFixture();f.failRead();assert.throws(()=>f.context.archiveDashboardHistoryV1(),/read failed/);assert.equal(f.rows().length,3);assert.equal(f.deletions(),0);});
test('migration drains verified rows and is safe to rerun',()=>{const f=migrationFixture();assert.equal(f.context.archiveDashboardHistoryV1().archivedRows,2);assert.equal(f.rows().length,1);const files=f.files.length;assert.equal(f.context.archiveDashboardHistoryV1().archivedRows,0);assert.equal(f.files.length,files);});
test('report dependencies block source cleanup',()=>{const f=migrationFixture();f.context.archiveDependentSheetsV1_=()=>['Сон'];const result=f.context.archiveDashboardHistoryV1();assert.equal(result.blocked.length,1);assert.equal(f.rows().length,3);assert.equal(f.deletions(),0);});
