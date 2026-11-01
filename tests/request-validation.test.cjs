const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');

function fixture() {
  let locks = 0, logs = 0;
  const context = vm.createContext({
    console, Date, Map, Set,
    PropertiesService: {getScriptProperties: () => ({getProperty: name => name === 'API_TOKEN' ? 'good' : null})},
    LockService: {getScriptLock: () => {locks++;return {tryLock:()=>true,releaseLock(){}};}}
  });
  vm.runInContext(fs.readFileSync('apps-script/Code.gs', 'utf8'), context);
  context.constantTimeTokenEquals_ = (a,b)=>a===b;
  context.json_ = v=>v;
  context.getSpreadsheet_ = () => { logs++;throw Error('should not open spreadsheet'); };
  return {context, send: payload => context.doPost({postData:{contents:JSON.stringify(payload)}}),
    locks:()=>locks, logs:()=>logs};
}

test('unknown client RPC does not spam HC_Журнал with healthSync errors', () => {
  const f=fixture();
  for(let i=0;i<3;i++){
    const r=f.send({token:'good',action:'energyBalanceV1',goalWeightKg:90});
    assert.equal(r.errorCode,'UNSUPPORTED_ACTION');
    assert.match(r.message,/energyBalanceV1/);
  }
  assert.equal(f.locks(),0);assert.equal(f.logs(),0);
});

test('legacy sync without days returns a bounded input error before any sheet writes', () => {
  const f=fixture();
  const r=f.send({token:'good',action:'healthSyncV3'});
  assert.equal(r.errorCode,'INVALID_PAYLOAD');
  assert.equal(f.locks(),0);assert.equal(f.logs(),0);
});

test('authentication still takes precedence over unsupported action',()=>{
  const f=fixture();
  const r=f.send({token:'wrong',action:'energyBalanceV1'});
  assert.equal(r.ok,false);assert.match(r.message,/API-токен/);
  assert.equal(f.locks(),0);
});
