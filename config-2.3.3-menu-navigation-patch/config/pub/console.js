(function () {
'use strict';
const build='233-nav2',root=document.getElementById('notice');
const previous=window.ConfigConsole233;
if(previous&&previous.build===build&&previous.root===root){previous.start();return;}
let startPromise=null,started=false,page='packages';
const $=id=>document.getElementById(id);let token='',version='',openedPackage='',openedFile='',busy=false,visiblePackages=[],commonConfigFiles=[],secretEdit=null,secretEditStatus=null;
function notice(text){if(root&&root.isConnected)root.textContent=text;}
function bind(id,event,handler){const element=$(id);if(element)element[event]=handler;}
function text(id,value){const element=$(id);if(element)element.textContent=value;}

function options(id,items){if(!$(id))return;$(id).replaceChildren(...items.map(text=>{const o=document.createElement('option');o.value=text;o.textContent=text;return o;}));}
function cell(row,text){const td=document.createElement('td');td.textContent=text;row.append(td);return td;}
function linkCell(row,text,url){const td=document.createElement('td'),a=document.createElement('a');a.textContent=text;a.href=url;td.append(a);row.append(td);}
async function request(action,extra={}){
 const res=await fetch('/invoke/config.services/dispatch',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded','Accept':'application/json'},body:new URLSearchParams({action,token,...extra}),cache:'no-store'});
 const text=await res.text();if(!res.ok)throw Error('IS request failed ('+res.status+'). Verify login, ACLs and package compilation.');let data;
 try{data=JSON.parse(text);if(data.result!==undefined)data=typeof data.result==='string'?JSON.parse(data.result):data.result;}
 catch(e){const doc=new DOMParser().parseFromString(text,'application/xml');const node=doc.querySelector('result')||doc.querySelector('[name="result"]');if(!node)throw Error('No service result. Check IS server log.');data=JSON.parse(node.textContent);}
 if(data.error)throw Error(data.error);return data;
}
function action(fn){return async()=>{if(busy)return;busy=true;try{await fn();}catch(e){notice(e.message);}finally{busy=false;}};}
function invalidate(){version='';openedPackage='';openedFile='';$('content').value='';$('save').disabled=true;$('editor').classList.add('hidden');}
async function status(){const data=await request('status');token=data.token;text('environment',data.environment.toUpperCase());text('loadedAt',data.loadedAt);text('version',data.version+' | Build: '+(data.buildId||'OLDER SERVER CODE')+' | Root: '+(data.packagesRoot||'unreported')+' | Folders: '+(data.configurationDirectories||[]).join(', ')+' | Files: '+(data.commonConfigFiles||[]).join(', '));
 visiblePackages=data.packages;commonConfigFiles=(data.commonConfigFiles||['config.cnf']).map(name=>name.toLowerCase());options('package',page==='files'?['All matching packages',...visiblePackages]:visiblePackages);const query=new URLSearchParams(location.search).get('package');if(query&&visiblePackages.includes(query)&&$('package'))$('package').value=query;
 const body=$('packageRows');if(body){body.replaceChildren();for(const pkg of visiblePackages){const row=document.createElement('tr');linkCell(row,pkg,'files.dsp?package='+encodeURIComponent(pkg));cell(row,pkg+'/config');linkCell(row,'Show files','files.dsp?package='+encodeURIComponent(pkg));linkCell(row,'Encrypted values','encrypted.dsp?package='+encodeURIComponent(pkg));body.append(row);}}return data;}
async function files(){
 invalidate();const selected=$('package').value,all=selected==='All matching packages';
 const body=$('fileRows');body.replaceChildren();options('file',[]);$('open').disabled=all||!selected;$('create').disabled=all||!selected;
 const packages=all?visiblePackages:(selected?[selected]:[]);let names=[];
 for(const pkg of packages){const data=await request('files',{packageName:pkg});if(!all)names=data.files;
  for(const file of data.files){if(all&&!commonConfigFiles.includes(file.toLowerCase()))continue;
   const row=document.createElement('tr');cell(row,pkg);cell(row,pkg+'/config');const td=cell(row,file),open=document.createElement('button');open.textContent='View / Edit';
   open.onclick=action(async()=>{$('package').value=pkg;await files();$('file').value=file;await read();});td.append(' ',open);cell(row,file==='config.secrets.cnf'?'Encrypted store':commonConfigFiles.includes(file.toLowerCase())?'Common':'Environment');body.append(row);
  }
 }
 options('file',names);if(!body.children.length){const row=document.createElement('tr');const td=cell(row,all?'No configured common files found. Check Server Cache for build/path settings; run dispatch action=files for directory diagnostics.':'No configuration files found.');td.colSpan=4;body.append(row);}
}
async function read(){const pkg=$('package').value,file=$('file').value;if(!file)throw Error('Select or create a file.');const data=await request('read',{packageName:pkg,fileName:file});openedPackage=pkg;openedFile=file;version=data.version;$('content').value=data.content;$('save').disabled=false;$('filePath').textContent=pkg+'/config/'+file;$('editor').classList.remove('hidden');notice('File read from disk. Save does not update the active cache.');}
async function secrets(){
 const body=$('secretRows');body.replaceChildren();const data=await request('secretsAll');
 const fragment=document.createDocumentFragment();
 for(const entry of data.entries.filter(item=>item.packageName===$('package').value)){const pkg=entry.packageName,row=document.createElement('tr');cell(row,pkg);
  if(entry.malformed){cell(row,'Malformed reference');cell(row,'Correct reference syntax');cell(row,(entry.keys||[]).join(', '));linkCell(row,'Open file','files.dsp?package='+encodeURIComponent(pkg));}
  else{cell(row,entry.handle);const state=cell(row,entry.configured?'Configured':'Missing - add manually');cell(row,(entry.keys||[]).join(', '));const td=cell(row,'');const edit=document.createElement('button');edit.textContent=entry.configured?'Update value':'Add value';edit.onclick=action(async()=>{const info=await request('secretEditInfo',{packageName:pkg});secretEdit={packageName:pkg,handle:entry.handle,version:info.version};secretEditStatus=state;$('secretEditTitle').textContent=pkg+' / '+entry.handle;$('secretEditValue').value='';$('secretEditPanel').classList.remove('hidden');$('secretEditValue').focus();});td.append(edit);}
  fragment.append(row);
 }
 if(!data.entries.some(item=>item.packageName===$('package').value)){const row=document.createElement('tr');const td=cell(row,'No encrypted references or stored handles in the selected package. Reload Configuration to discover saved file changes.');td.colSpan=5;fragment.append(row);}
 body.replaceChildren(fragment);
}

function bindControls(){
bind('secretEditCancel','onclick',()=>{secretEdit=null;$('secretEditValue').value='';$('secretEditPanel').classList.add('hidden');});
bind('secretEditSave','onclick',action(async()=>{if(!secretEdit)throw Error('Choose an encrypted entry.');const value=$('secretEditValue').value;if(!value)throw Error('Enter the new secret value.');try{const data=await request('saveHandle',{...secretEdit,secret:value});secretEdit=null;$('secretEditPanel').classList.add('hidden');notice(data.message);window.scrollTo({top:0,behavior:'auto'});if(secretEditStatus)secretEditStatus.textContent='Saved on disk - reload pending';secretEditStatus=null;}finally{$('secretEditValue').value='';}}));

bind('package','onchange',action(async()=>{if(page==='encrypted')await secrets();else await files();}));bind('file','onchange',invalidate);bind('open','onclick',action(read));
bind('save','onclick',action(async()=>{await request('save',{packageName:openedPackage,fileName:openedFile,content:$('content').value,version});invalidate();notice('File saved successfully. Reload Configuration to apply the saved values to memory.');window.scrollTo({top:0,behavior:'auto'});$('notice').scrollIntoView({block:'start',behavior:'auto'});}));
bind('create','onclick',action(async()=>{const name=$('newFile').value;const data=await request('create',{packageName:$('package').value,fileName:name});await files();$('file').value=name;await read();notice(data.message);}));
bind('saveSecret','onclick',action(async()=>{if(!version||openedFile==='config.secrets.cnf')throw Error('Open a normal configuration file first.');if(!confirm('Save encrypted property to disk? This rewrites properties formatting. Save editor changes first.'))return;try{const data=await request('saveSecret',{packageName:openedPackage,fileName:openedFile,key:$('key').value,handle:$('handle').value,secret:$('secret').value,version});await read();notice(data.message);}finally{$('secret').value='';}}));
bind('reset','onclick',action(async()=>{if(!confirm('Reload saved configuration, then clear IS service-result caches on THIS node? Unsaved edits are excluded.'))return;const data=await request('resetAll');let message=data.message;try{await status();if(page==='encrypted')await secrets();}catch(e){message+=' Status refresh failed: '+e.message;}notice(message);}));
bind('refresh','onclick',action(async()=>{await status();if(page==='files')await files();if(page==='encrypted')await secrets();notice('Package metadata refreshed. Cache unchanged.');}));
}

function checkPage(){
 const required={packages:['packageRows'],files:['package','file','fileRows','open','create','content','save','editor'],encrypted:['package','secretRows','secretEditPanel','secretEditValue','secretEditTitle','secretEditSave','secretEditCancel'],settings:['loadedAt','version','reset'],migration:[]};
 if(!Object.prototype.hasOwnProperty.call(required,page))throw Error('Unknown configuration page: '+page);
 const missing=required[page].filter(id=>!$(id));
 if(missing.length)throw Error('Console startup failed: missing page controls '+missing.map(id=>'#'+id).join(', ')+'. Install all DSP files from the navigation patch.');
}
function start(force){
 if(!root||!root.isConnected)return Promise.resolve();
 if(startPromise)return startPromise;
 if(started&&!force)return Promise.resolve();
 startPromise=(async()=>{
  busy=true;
  try{
   page=document.body.dataset.page||'packages';
   notice('Connecting to Integration Server ('+build+')…');
   checkPage();bindControls();
   for(const item of document.querySelectorAll('[data-view]'))item.classList.toggle('hidden',item.dataset.view!==page);
   for(const item of document.querySelectorAll('aside a'))item.classList.toggle('active',item.dataset.nav===page);
   if($('selector'))$('selector').classList.toggle('hidden',page!=='files'&&page!=='encrypted');
   await status();
   if(page==='files')await files();
   if(page==='encrypted')await secrets();
   notice('Connected. Changes affect this IS node only.');
   started=true;
  }catch(e){
   notice('Configuration console: '+e.message);
   console.error('Configuration console startup ('+build+') failed:',e);
  }finally{busy=false;}
 })();
 startPromise.finally(()=>{startPromise=null;});
 return startPromise;
}
// Load a complete document even when an admin shell handles menu clicks.
// Preserve modified clicks and links that explicitly target another window.
document.addEventListener('click',event=>{
 if(event.defaultPrevented||event.button!==0||event.ctrlKey||event.metaKey||event.shiftKey||event.altKey)return;
 const link=event.target.closest&&event.target.closest('a[href]');
 if(!link||link.hasAttribute('download')||(link.target&&link.target!=='_self'))return;
 const url=new URL(link.href,location.href);
 if(url.origin!==location.origin||!/^\/config\/(index|packages|files|encrypted|migration|settings)\.dsp$/.test(url.pathname))return;
 event.preventDefault();event.stopImmediatePropagation();
 window.location.assign(url.href);
},true);
window.addEventListener('pageshow',event=>{if(event.persisted)start(true);});
window.ConfigConsole233={build,root,start};
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',()=>start(),{once:true});
else start();
})();
