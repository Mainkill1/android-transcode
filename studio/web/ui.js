/* Forma HTML workbench: one source, one validated export, no simulated encoding. */
(() => {
  'use strict';
  const $=(s,p=document)=>p.querySelector(s), $$=(s,p=document)=>[...p.querySelectorAll(s)];
  const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const icon=n=>`<svg class="icon" aria-hidden="true"><use href="#i-${n}"/></svg>`;
  const copy=v=>JSON.parse(JSON.stringify(v));
  const VIDEO={mp4:['h264','hevc','av1'],mkv:['h264','hevc','vp9','av1'],mov:['h264','hevc'],webm:['vp9','av1']};
  const VENC={h264:'libx264',hevc:'libx265',vp9:'libvpx-vp9',av1:'libaom-av1'};
  const AENC={mp3:'libmp3lame',m4a:'aac',flac:'flac',wav:'pcm_s16le',opus:'libopus'};
  const VN={mp4:'MP4 · Most devices',mkv:'MKV · Flexible container',mov:'MOV · Editing workflows',webm:'WebM · Web video'};
  const AN={mp3:'MP3 · Plays almost anywhere',m4a:'M4A / AAC · Compact & clear',flac:'FLAC · Lossless compression',wav:'WAV · Uncompressed PCM',opus:'Opus · Efficient audio'};
  const CN={h264:'H.264 / AVC',hevc:'H.265 / HEVC',vp9:'VP9',av1:'AV1'};
  const state={page:'home',view:'video',inspector:'trim',shelf:false,advanced:false,source:null,settings:null,
    engine:{available:false,encoders:[],token:null},engineChecked:false,history:[],future:[],selected:0,
    jobs:[],plan:null,planError:'',planPending:false,videoFormat:'mp4',audioFormat:'mp3',pendingView:null};
  const media=$('#media');let mediaObjectUrl=null,toastTimer,planTimer,planSequence=0,playIndex=0;
  function defaults(src){const audio=!src.hasVideo;return {mode:audio?'audio':'video',format:audio?'mp3':'mp4',videoCodec:'h264',
    rateMode:'quality',crf:23,videoBitrate:2500,preset:'fast',resolution:'source',fps:'source',audioBitrate:192,audioTrack:0,
    channels:'source',sampleRate:'source',segments:src.duration?[{start:0,end:src.duration}]:[],crop:{left:0,right:0,top:0,bottom:0},
    rotation:0,flip:false,speed:1,volume:1,mute:false,normalize:false,fadeIn:0,fadeOut:0,stripMetadata:true,filename:''};}
  function time(v,precise=false){if(!Number.isFinite(v))return 'Unknown';const minutes=Math.floor(v/60);const sec=v-minutes*60;return `${String(minutes).padStart(2,'0')}:${precise?sec.toFixed(2).padStart(5,'0'):String(Math.floor(sec)).padStart(2,'0')}`;}
  function size(n){return n>=1073741824?`${(n/1073741824).toFixed(2)} GiB`:n>=1048576?`${(n/1048576).toFixed(1)} MB`:`${Math.max(1,Math.round(n/1024))} KB`;}
  function duration(){return state.settings?state.settings.segments.reduce((n,r)=>n+r.end-r.start,0)/state.settings.speed:0;}
  function toast(text){$('#toast').textContent=text;$('#toast').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('#toast').hidden=true,4500);}
  function busy(on,text='Inspecting your media…'){$('#busy-label').textContent=text;$('#busy').hidden=!on;}
  function help(){$('#help-dialog').showModal();}
  $('#runner-help').onclick=help;$('#engine-badge').onclick=help;$('#help-ok').onclick=()=>$('#help-dialog').close();$('#close-help').onclick=()=>$('#help-dialog').close();
  async function api(path,data){
    const options={headers:{'X-Forma-Token':state.engine.token||''}};
    if(data!==undefined){options.method='POST';options.headers['Content-Type']='application/json';options.body=JSON.stringify(data);}
    const r=await fetch(path,options);let payload;
    try{payload=await r.json();}catch{throw new Error('The local runner returned an unreadable response.');}
    if(!r.ok)throw new Error(payload.error||'The local runner could not complete the request.');return payload;
  }
  async function connect(){
    try{if(!/^https?:$/.test(location.protocol))throw new Error('Standalone preview');state.engine=await api('/api/session');}
    catch{state.engine={available:false,encoders:[],token:null};}
    state.engineChecked=true;$('#engine-label').textContent=state.engine.available?'FFmpeg connected':'Layout preview';
    $('#engine-badge').classList.toggle('engine-ready',state.engine.available);if(state.source)render();
    if(state.engine.available)pollQueue();
  }
  function available(enc){return !state.engine.available||state.engine.encoders.includes(enc);}
  function outputOptions(mode){
    if(mode==='audio')return Object.keys(AN).map(f=>({value:f,label:AN[f],disabled:!available(AENC[f])}));
    return Object.keys(VN).map(f=>({value:f,label:VN[f],disabled:!VIDEO[f].some(c=>available(VENC[c]))}));
  }
  function shelf(open){
    state.shelf=open;$('#shelf').hidden=!open;document.body.classList.toggle('shelf-open',open);$('#menu').setAttribute('aria-expanded',open);
    const modal=open&&innerWidth<1000;$('#scrim').hidden=!modal;$('#main').inert=modal;$('#appbar').inert=modal;$('#export-bar').inert=modal;
    if(modal){$('#shelf').setAttribute('role','dialog');$('#shelf').setAttribute('aria-modal','true');$('#close-shelf').focus();}
    else{$('#shelf').removeAttribute('role');$('#shelf').removeAttribute('aria-modal');if(!open)$('#menu').focus();}
  }
  $('#menu').onclick=()=>shelf(!state.shelf);$('#close-shelf').onclick=()=>shelf(false);$('#scrim').onclick=()=>shelf(false);
  addEventListener('resize',()=>{if(state.shelf)shelf(true);draw();});
  $('#shelf').addEventListener('keydown',e=>{
    if(e.key==='Escape'){shelf(false);return;}if(e.key!=='Tab'||innerWidth>=1000)return;
    const items=$$('button:not(:disabled)',$('#shelf'));const first=items[0],last=items.at(-1);
    if(e.shiftKey&&document.activeElement===first){e.preventDefault();last.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus();}
  });
  function navigate(page){
    if(['video','audio','edit'].includes(page)){
      if(!state.source){state.pendingView=page;page='home';$('#home-message').textContent='Select media or enter a media URL to continue.';}
      else{setView(page);page='workspace';}
    }
    state.page=page;if(innerWidth<1000&&state.shelf)shelf(false);
    $('#home').hidden=page!=='home';$('#workspace').hidden=page!=='workspace';$('#queue-screen').hidden=page!=='queue';$('#export-bar').hidden=page!=='workspace';
    if(page!=='workspace')media.pause();
    $$('#shelf [data-nav]').forEach(b=>b.classList.toggle('active',b.dataset.nav===(page==='workspace'?state.view:page)));
    if(page==='workspace')render();if(page==='queue')renderQueue();window.scrollTo({top:0,behavior:'instant'});
  }
  $('#brand').onclick=e=>{e.preventDefault();navigate('home');};
  document.addEventListener('click',e=>{
    const nav=e.target.closest('[data-nav]');if(nav){navigate(nav.dataset.nav);return;}
    const tab=e.target.closest('[data-tab]');if(tab){setView(tab.dataset.tab);return;}
    const jump=e.target.closest('[data-jump]');if(jump){
      if(!state.source){state.pendingView=jump.dataset.jump==='advanced'?'video':'edit';navigate('home');$('#home-message').textContent='Add a source first. Your tools will open after inspection.';return;}
      if(jump.dataset.jump==='advanced'){navigate(state.settings.mode);state.advanced=true;render();$('#advanced-toggle')?.scrollIntoView({behavior:'smooth',block:'start'});}
      else{navigate('edit');state.inspector=jump.dataset.jump;renderInspector();}return;
    }
    const sample=e.target.closest('[data-sample]');if(sample)loadSample(sample.dataset.sample);
    const tool=e.target.closest('[data-inspector]');if(tool){state.inspector=tool.dataset.inspector;renderInspector();}
    const goal=e.target.closest('[data-goal]');if(goal)applyGoal(goal.dataset.goal);
    const ratio=e.target.closest('[data-ratio]');if(ratio)applyCrop(ratio.dataset.ratio);
    const clip=e.target.closest('[data-clip]');if(clip){state.selected=Number(clip.dataset.clip);media.currentTime=state.settings.segments[state.selected].start;renderTimeline();renderInspector();}
    const nudge=e.target.closest('[data-trim-nudge]');if(nudge){
      const edge=nudge.dataset.trimNudge,r=state.settings?.segments[state.selected];
      if(r)setTrim(edge,r[edge]+Number(nudge.dataset.delta));return;
    }
    const here=e.target.closest('[data-trim-here]');if(here){setTrim(here.dataset.trimHere,media.currentTime);return;}
    const seek=e.target.closest('[data-seek]');if(seek&&state.source){media.pause();media.currentTime=Math.max(0,Math.min(state.source.duration,Math.round(media.currentTime*100)/100+Number(seek.dataset.seek)));updatePlayhead();return;}
    const move=e.target.closest('[data-move]');if(move)moveClip(Number(move.dataset.move));
    const cancel=e.target.closest('[data-cancel]');if(cancel)api(`/api/jobs/${cancel.dataset.cancel}/cancel`,{}).then(refreshQueue).catch(e=>toast(e.message));
  });
  function setView(view){
    if(!state.source)return;
    if(view==='video'&&!state.source.hasVideo){toast('This source contains audio only.');return;}
    if(view==='audio'&&!state.source.hasAudio){toast('This source has no audio track to export.');return;}
    if(view!=='edit'){
      if(state.settings.mode!==view){pushHistory();state.settings.mode=view;state.settings.format=view==='video'?state.videoFormat:state.audioFormat;
        if(view==='audio')state.settings.mute=false;
        fixCompatibility();schedulePlan();}
      media.pause();
    }
    state.view=view;render();
  }
  function fixCompatibility(){
    const s=state.settings;if(s.mode==='video'){
      const list=VIDEO[s.format]||VIDEO.mp4;
      if(!list.includes(s.videoCodec)||!available(VENC[s.videoCodec]))s.videoCodec=list.find(c=>available(VENC[c]))||list[0];
      state.videoFormat=s.format;
    }else state.audioFormat=s.format;
  }
  function pushHistory(){state.history.push(copy(state.settings));if(state.history.length>80)state.history.shift();state.future=[];}
  function change(patch){
    // Native blur/change can fire again while a field is being replaced.
    // A no-op must not re-enter rendering or create a duplicate undo entry.
    if(Object.entries(patch).every(([key,value])=>JSON.stringify(state.settings[key])===JSON.stringify(value)))return;
    pushHistory();Object.assign(state.settings,patch);fixCompatibility();state.selected=Math.min(state.selected,state.settings.segments.length-1);state.planError='';render();schedulePlan();}
  function history(back){const from=back?state.history:state.future,to=back?state.future:state.history;if(!from.length)return;to.push(copy(state.settings));state.settings=from.pop();if(state.view!=='edit')state.view=state.settings.mode;state.selected=Math.min(state.selected,state.settings.segments.length-1);fixCompatibility();render();schedulePlan();}
  $('#undo').onclick=()=>history(true);$('#redo').onclick=()=>history(false);
  function applyGoal(goal){
    if(state.settings.mode==='audio'){change({audioBitrate:{small:96,balanced:192,high:320}[goal]});return;}
    const opts={small:{crf:28,resolution:'720'},balanced:{crf:23,resolution:'source'},high:{crf:18,resolution:'source'}}[goal];change({...opts,rateMode:'quality'});
  }
  function selectFile(){$('#file-input').click();}
  $('#select-media').onclick=selectFile;$('#change-source').onclick=selectFile;
  $('#file-input').onchange=e=>{if(e.target.files[0])importLocal(e.target.files[0]);e.target.value='';};
  for(const name of ['dragenter','dragover'])document.addEventListener(name,e=>{if(e.dataTransfer?.types.includes('Files')){e.preventDefault();document.body.classList.add('dragging');}});
  document.addEventListener('dragleave',e=>{if(!e.relatedTarget)document.body.classList.remove('dragging');});
  document.addEventListener('drop',e=>{document.body.classList.remove('dragging');if(e.dataTransfer?.files.length){e.preventDefault();importLocal(e.dataTransfer.files[0]);}});
  function localMetadata(file,url){return new Promise(resolve=>{
    const v=document.createElement('video');let done=false;
    const end=info=>{if(done)return;done=true;v.removeAttribute('src');v.load();resolve(info);};
    const audio=file.type.startsWith('audio/')||/\.(mp3|m4a|aac|wav|flac|ogg|opus)$/i.test(file.name);
    v.preload='metadata';v.onloadedmetadata=()=>end({duration:Number.isFinite(v.duration)?v.duration:null,width:v.videoWidth,height:v.videoHeight,
      hasVideo:!!v.videoWidth,hasAudio:true,audioTracks:[{index:0,codec:'unprobed',channels:2,sampleRate:48000}],hdr:false,verified:false});
    v.onerror=()=>end({duration:null,width:0,height:0,hasVideo:!audio,hasAudio:true,audioTracks:[],hdr:false,verified:false});
    v.src=url;setTimeout(()=>v.onerror?.(),6500);
  });}
  async function importLocal(file){
    busy(true);let preview=URL.createObjectURL(file);
    try{
      let src;
      if(state.engine.available){
        const r=await fetch(`/api/upload?name=${encodeURIComponent(file.name)}`,{method:'POST',headers:{'X-Forma-Token':state.engine.token,'Content-Type':'application/octet-stream'},body:file});
        const payload=await r.json();if(!r.ok)throw new Error(payload.error||'The media could not be inspected.');src={...payload,backend:true};
      }else src={id:'local-'+Date.now(),name:file.name,size:file.size,...await localMetadata(file,preview),backend:false};
      activate(src,preview);preview=null;
    }catch(e){toast(e.message);$('#home-message').textContent=e.message;}
    finally{if(preview)URL.revokeObjectURL(preview);busy(false);}
  }
  async function loadSample(kind){
    busy(true,'Opening the sample…');
    try{
      if(state.engine.available){const src=await api('/api/sample',{kind});activate({...src,backend:true},`/media/${src.id}`);}
      else{
        const ext=kind==='audio'?'wav':'mp4';const url=window.FORMA_SAMPLES?.[kind]||`/sample.${ext}`;
        const r=await fetch(url);if(!r.ok)throw new Error('Open the packaged standalone HTML or start the local runner to use the sample.');
        const blob=await r.blob();await importLocal(new File([blob],`Studio sample.${ext}`,{type:kind==='audio'?'audio/wav':'video/mp4'}));
      }
    }catch(e){toast(e.message);}finally{busy(false);}
  }
  $('#url-form').onsubmit=async e=>{
    e.preventDefault();const raw=$('#media-url').value.trim();let u;
    try{u=new URL(raw);if(!['http:','https:'].includes(u.protocol)||u.username||u.password)throw new Error();}
    catch{$('#home-message').textContent='Use a direct HTTP or HTTPS media URL without embedded credentials.';return;}
    if(!state.engine.available){$('#home-message').textContent='URL inspection and download require the local runner. Start python server.py and open the app there.';return;}
    busy(true,'Downloading and inspecting your media…');$('#home-message').textContent='';
    try{const src=await api('/api/url',{url:u.href});activate({...src,backend:true},`/media/${src.id}`);}
    catch(e){$('#home-message').textContent=e.message;}finally{busy(false);}
  };
  function activate(src,url){
    media.pause();if(mediaObjectUrl)URL.revokeObjectURL(mediaObjectUrl);mediaObjectUrl=url.startsWith('blob:')?url:null;
    state.source=src;state.settings=defaults(src);state.selected=0;state.history=[];state.future=[];state.advanced=false;
    state.videoFormat='mp4';state.audioFormat='mp3';state.plan=null;state.planError='';
    const opts=outputOptions(state.settings.mode);const supported=opts.find(o=>!o.disabled);if(supported)state.settings.format=supported.value;
    fixCompatibility();$('#preview-error').hidden=true;media.src=url;media.load();
    state.view=state.pendingView||state.settings.mode;state.pendingView=null;if(state.view==='video'&&!src.hasVideo)state.view='audio';
    if(state.view==='audio'&&src.hasAudio){state.settings.mode='audio';state.settings.format=state.audioFormat;}
    if(state.view==='audio'&&!src.hasAudio)state.view='video';
    navigate('workspace');window.FormaTimeline?.refresh();schedulePlan();
  }
  function select(label,key,options,value,helptext=''){
    const list=options.map(o=>typeof o==='string'?{value:o,label:o}:o);
    return `<label class="field"><span>${esc(label)}</span><select data-setting="${key}" aria-label="${esc(label)}">${list.map(o=>`<option value="${esc(o.value)}" ${String(o.value)===String(value)?'selected':''} ${o.disabled?'disabled':''}>${esc(o.label)}${o.disabled?' — unavailable':''}</option>`).join('')}</select>${helptext?`<small>${esc(helptext)}</small>`:''}</label>`;
  }
  function input(label,key,value,opts={}){
    return `<label class="field"><span>${esc(label)}${opts.suffix?`<span class="field-value">${esc(opts.suffix)}</span>`:''}</span><input data-setting="${key}" aria-label="${esc(label)}" type="${opts.type||'number'}" value="${esc(value)}" ${opts.min!==undefined?`min="${opts.min}"`:''} ${opts.max!==undefined?`max="${opts.max}"`:''} step="${opts.step||1}" ${opts.placeholder?`placeholder="${esc(opts.placeholder)}"`:''}>${opts.help?`<small>${esc(opts.help)}</small>`:''}</label>`;
  }
  function toggle(label,key,value,description=''){
    return `<label class="switch-row"><span>${esc(label)}${description?`<small>${esc(description)}</small>`:''}</span><input type="checkbox" data-setting="${key}" aria-label="${esc(label)}" ${value?'checked':''}></label>`;
  }
  function audioFields(){const s=state.settings,src=state.source;
    if(!src.hasAudio)return '<p class="helper">This source has no audio track. Video output will be silent.</p>';
    return select('Source audio track','audioTrack',src.audioTracks.map((t,i)=>({value:i,label:`Track ${i+1} · ${String(t.codec||'audio').toUpperCase()} · ${t.channels} ch${t.language&&t.language!=='und'?' · '+t.language:''}`})),s.audioTrack)+
      `<div class="two-cols">${select('Channels','channels',[{value:'source',label:'Keep original'},{value:'1',label:'Mono'},{value:'2',label:'Stereo'}],s.channels)}${select('Sample rate','sampleRate',[{value:'source',label:'Keep original'},...['22050','32000','44100','48000','96000'].map(x=>({value:x,label:`${Number(x)/1000} kHz`}))],s.sampleRate)}</div>`+
      (!['flac','wav'].includes(s.format)?select('Audio bitrate','audioBitrate',[96,128,160,192,256,320].map(x=>({value:x,label:`${x} kb/s`})),s.audioBitrate):'')+
      (s.format==='opus'?'<p class="helper">Opus output uses 48 kHz. The runner resamples if needed.</p>':'');
  }
  function renderConversion(){const s=state.settings,audio=s.mode==='audio',lossless=['flac','wav'].includes(s.format);
    const selected=audio?(s.audioBitrate<=96?'small':s.audioBitrate>=320?'high':'balanced'):(s.rateMode==='quality'?(s.crf>=28?'small':s.crf<=18?'high':'balanced'):'');
    const labels=audio?{small:['Smaller','Good for speech'],balanced:['Balanced','Everyday listening'],high:['Higher quality','More audio detail']}:{small:['Smaller file','Less space, easy sharing'],balanced:['Balanced','A little of everything'],high:['Higher quality','Keep more detail']};
    $('#conversion-panel').innerHTML=`<div class="conversion-content">${!state.engine.available?'<p class="engine-note">Layout preview · Start the included local runner to enable real FFmpeg export.</p>':''}
      ${lossless?'<p class="helper"><span class="pill">LOSSLESS OUTPUT CODEC</span>FLAC uses lossless compression; WAV uses 16-bit PCM. Neither restores detail lost in the source.</p>':`<h2 class="section-heading">${audio?'How should it sound?':'What matters most?'}</h2><div class="goal-row">${Object.entries(labels).map(([id,t])=>`<button data-goal="${id}" class="goal-button ${selected===id?'selected':''}" aria-pressed="${selected===id}"><b>${t[0]}</b><span>${t[1]}</span></button>`).join('')}</div>`}
      ${select('Save as','format',outputOptions(s.mode),s.format)}
      ${audio?'<p class="helper">Convert audio files, or extract a video’s sound without its picture.</p>':select('Picture size','resolution',[{value:'source',label:'Keep original size'},{value:'720',label:'Fit within 720p'},{value:'1080',label:'Fit within 1080p'},{value:'2160',label:'Fit within 4K'},{value:'480',label:'Fit within 480p'}],s.resolution,'Keeps the shape of your picture. Smaller sources are not upscaled.')}
      <button id="advanced-toggle" class="advanced-toggle" aria-expanded="${state.advanced}" aria-controls="advanced-content">${icon('sliders')}More settings<span>${state.advanced?'Hide details':'Optional'}</span>${icon('chevron')}</button>
      <div id="advanced-content" class="advanced-content" ${state.advanced?'':'hidden'}>
      ${!audio?`${select('Video encoder','videoCodec',VIDEO[s.format].map(c=>({value:c,label:CN[c],disabled:!available(VENC[c])})),s.videoCodec)}<div class="two-cols">${select('Rate control','rateMode',[{value:'quality',label:'Constant quality'},{value:'bitrate',label:'Average bitrate'}],s.rateMode)}${s.rateMode==='quality'?input('Quality value (CRF)','crf',s.crf,{min:0,max:['h264','hevc'].includes(s.videoCodec)?51:63,help:'Lower values keep more detail.'}):input('Video bitrate (kb/s)','videoBitrate',s.videoBitrate,{min:100,max:200000})}</div><div class="two-cols">${select('Frame rate','fps',[{value:'source',label:'Keep original'},...['24','25','30','50','60'].map(x=>({value:x,label:x+' fps'}))],s.fps)}${['h264','hevc'].includes(s.videoCodec)?select('Encoding effort','preset',[{value:'ultrafast',label:'Very fast'},{value:'fast',label:'Fast'},{value:'medium',label:'Balanced'},{value:'slow',label:'Slower / more effort'}],s.preset):'<p class="helper">This software encoder uses a fixed practical speed setting in this workbench.</p>'}</div><p class="group-title">Sound track</p>${toggle('Remove audio','mute',s.mute)}`:''}
      ${audioFields()}<p class="group-title">Output</p>${input('Output file name','filename',s.filename,{type:'text',placeholder:'Automatic from source name',help:'The correct extension is added automatically.'})}${toggle('Remove metadata','stripMetadata',s.stripMetadata,'Subtitles, chapters, and attachments are not exported by this slice.')}
      </div><button id="edit-shortcut" class="edit-shortcut">${icon('cut')}<div><b>${audio?'Trim or adjust the sound':'Need a quick edit first?'}</b><span>${audio?'Trim, split, change speed, and add fades.':'Trim, split, crop, rotate, and adjust the sound.'}</span></div>${icon('arrow')}</button>
      ${hasEdits()?`<div class="cut-badge">Edits stay active on export. ${state.settings.segments.length} kept clip(s).<button id="review-edits">Review</button></div>`:''}</div>`;
    $('#advanced-toggle').onclick=()=>{state.advanced=!state.advanced;renderConversion();};$('#edit-shortcut').onclick=()=>setView('edit');if($('#review-edits'))$('#review-edits').onclick=()=>setView('edit');
  }
  function hasEdits(){const s=state.settings;return s.segments.length!==1||s.segments[0]?.start!==0||Math.abs((s.segments[0]?.end||0)-(state.source.duration||0))>.01||Object.values(s.crop).some(Boolean)||s.rotation||s.flip||s.speed!==1||s.volume!==1||s.mute||s.normalize||s.fadeIn||s.fadeOut;}
  function render(){if(!state.source)return;
    document.body.classList.toggle('editing',state.view==='edit');
    const focus=document.activeElement?.dataset.setting;
    $('#source-name').textContent=state.source.name;
    $('#source-meta').textContent=[state.source.hasVideo?`${state.source.width||'?'} × ${state.source.height||'?'}`:'Audio source',time(state.source.duration),size(state.source.size),state.source.backend?'Inspected by FFprobe':'Browser preview only'].join('  ·  ');
    $('#source-icon').innerHTML=icon(state.source.hasVideo?'video':'audio');
    $('#workspace-eyebrow').textContent=state.view==='edit'?'EDIT BEFORE EXPORT':'CONVERT';
    $('#workspace-title').textContent=state.view==='edit'?'Edit your media.':state.view==='audio'?'Make it sound right.':'Choose your output.';
    $$('.workspace-tabs button').forEach(b=>b.setAttribute('aria-selected',String(b.dataset.tab===state.view)));
    $('#tab-video').disabled=!state.source.hasVideo;$('#tab-audio').disabled=!state.source.hasAudio;
    $$('#shelf [data-nav]').forEach(b=>b.classList.toggle('active',b.dataset.nav===state.view));
    $('#conversion-panel').hidden=state.view==='edit';$('#edit-panel').hidden=state.view!=='edit';$('#history-buttons').hidden=state.view!=='edit';
    $('#undo').disabled=!state.history.length;$('#redo').disabled=!state.future.length;
    if(state.view!=='edit')renderConversion();else{renderTimeline();renderInspector();}
    media.playbackRate=state.settings.speed;media.volume=Math.max(0,Math.min(1,state.settings.volume));media.muted=state.settings.mute;
    $('#audio-art').hidden=state.source.hasVideo;$('#audio-art-name').textContent=state.source.name;$('#preview-canvas').hidden=!state.source.hasVideo;
    $('#scrub').max=state.source.duration||1;$('#total-time').textContent=time(state.source.duration,true);
    renderFooter();draw();if(focus){const el=$(`[data-setting="${focus}"]`,state.view==='edit'?$('#inspector-content'):$('#conversion-panel'));el?.focus({preventScroll:true});}
  }
  function renderFooter(){
    const s=state.settings;if(!s)return;
    const name=state.plan?.outputName||`${s.filename||state.source.name.replace(/\.[^.]+$/,'')+'-converted'}.${s.format}`;
    $('#output-name').textContent=name;
    $('#output-summary').textContent=`${s.format.toUpperCase()} · ${time(duration())} ${s.mode==='audio'?'audio':'video'}`;
    $('#convert-label').textContent=`${state.view==='edit'?'Export':'Convert'} ${s.mode}`;
    const ready=state.engine.available&&state.source.backend&&state.plan&&!state.planError&&!state.planPending;
    $('#convert').disabled=!ready;$('#add-queue').disabled=!ready;
    const message=state.planError||(!state.engine.available?'Preview only. Run python server.py from the included package to encode with FFmpeg.':'');
    $('#workspace-message').textContent=message;$('#workspace-message').hidden=!message;
  }
  function renderTimeline(){const s=state.settings;
    $('#clip-list').innerHTML=s.segments.map((r,i)=>`<button data-clip="${i}" class="clip ${state.selected===i?'selected':''}" aria-label="Select clip ${i+1}" aria-pressed="${state.selected===i}" style="flex-grow:${Math.max(.1,r.end-r.start)}"><b>Clip ${String(i+1).padStart(2,'0')}</b><span>${time(r.start,true)} – ${time(r.end,true)}</span></button>`).join('');
    $('#timeline-summary').textContent=`${s.segments.length} clip${s.segments.length!==1?'s':''} · ${time(duration(),true)} output`;
    $('#remove-clip').disabled=s.segments.length<2;$('#split').disabled=!state.source.duration;
    window.FormaTimeline?.refresh();
  }
  function trimNudges(edge){
    return `<div class="trim-nudges"><button data-trim-nudge="${edge}" data-delta="-0.1" aria-label="Move ${edge} earlier by 0.1 seconds">−</button><button data-trim-here="${edge}">Set ${edge} here</button><button data-trim-nudge="${edge}" data-delta="0.1" aria-label="Move ${edge} later by 0.1 seconds">+</button></div>`;
  }
  function clampTrim(edge,value,range,total){
    // Round the requested edge before clamping; never round a fractional source end past EOF.
    const gap=Math.min(.04,total),rounded=Math.round(value*100)/100;
    return edge==='start'?Math.max(0,Math.min(rounded,range.end-gap)):
      Math.min(total,Math.max(rounded,range.start+gap));
  }
  function setTrim(edge,value){
    if(!Number.isFinite(value)||!state.source?.duration)return;
    const clips=copy(state.settings.segments),r=clips[state.selected];if(!r)return;
    r[edge]=clampTrim(edge,value,r,state.source.duration);
    change({segments:clips});media.pause();media.currentTime=r[edge];updatePlayhead();
  }
  function renderInspector(){
    $$('#edit-panel [data-inspector]').forEach(b=>{b.setAttribute('aria-selected',String(b.dataset.inspector===state.inspector));b.disabled=b.dataset.inspector==='picture'&&!state.source.hasVideo;});
    if(!state.source.hasVideo&&state.inspector==='picture')state.inspector='trim';
    const s=state.settings,r=s.segments[state.selected],src=state.source;
    if(state.inspector==='trim'){
      $('#inspector-content').innerHTML=`<h2 class="inspector-title">Fine-tune the cut.</h2><p class="inspector-desc">Drag the timeline brackets for a visual cut. Use seconds below for exact adjustments.</p>${r?`<div class="two-cols"><div>${input('Start (seconds)','start',Number(r.start.toFixed(2)),{min:0,max:r.end-.04,step:.01})}${trimNudges('start')}</div><div>${input('End (seconds)','end',Number(r.end.toFixed(2)),{min:r.start+.04,max:src.duration,step:.01})}${trimNudges('end')}</div></div><p class="trim-step-label">Tap − / + for 0.1-second steps. “Here” uses the playhead.</p><div class="mini-actions"><button class="small-button" data-move="-1" ${state.selected===0?'disabled':''}>Move earlier</button><button class="small-button" data-move="1" ${state.selected===s.segments.length-1?'disabled':''}>Move later</button></div>`:'<p class="helper">A verified duration is needed for editing. Open this source with the local runner.</p>'}<div class="divider"></div>${select('Playback speed','speed',[.5,.75,1,1.25,1.5,2].map(x=>({value:x,label:x===1?'Normal speed':`${x}× speed`})),s.speed,'Audio tempo changes with the picture. Pitch is preserved.')}`;
    }else if(state.inspector==='picture'){
      $('#inspector-content').innerHTML=`<h2 class="inspector-title">Frame it your way.</h2><p class="inspector-desc">Crop first, then rotate and mirror. The original stays untouched.</p><div class="crop-presets"><button data-ratio="original">Original</button><button data-ratio="1">1:1</button><button data-ratio="1.7777777778">16:9</button><button data-ratio="0.5625">9:16</button><button data-ratio="1.3333333333">4:3</button></div><div class="two-cols">${input('Crop left (%)','crop.left',s.crop.left,{min:0,max:94,step:.1})}${input('Crop right (%)','crop.right',s.crop.right,{min:0,max:94,step:.1})}${input('Crop top (%)','crop.top',s.crop.top,{min:0,max:94,step:.1})}${input('Crop bottom (%)','crop.bottom',s.crop.bottom,{min:0,max:94,step:.1})}</div>${select('Rotate clockwise','rotation',[{value:0,label:'No rotation'},{value:90,label:'90° right'},{value:180,label:'180°'},{value:270,label:'90° left'}],s.rotation)}${toggle('Mirror horizontally','flip',s.flip)}<p class="helper">Crop coordinates are rounded to valid pixel boundaries for the selected video encoder.</p>`;
    }else{
      $('#inspector-content').innerHTML=`<h2 class="inspector-title">Give the sound some space.</h2><p class="inspector-desc">Volume, loudness and fades apply across the finished sequence.</p>${src.hasAudio?`${s.mode==='video'?toggle('Remove audio','mute',s.mute):''}${input('Volume','volume',s.volume,{type:'range',min:0,max:3,step:.05,suffix:`${Math.round(s.volume*100)}%`})}${toggle('Level loudness','normalize',s.normalize,'Single-pass normalization to a −16 LUFS target.')}
      <div class="divider"></div><div class="two-cols">${input('Fade in (seconds)','fadeIn',s.fadeIn,{min:0,max:duration(),step:.1})}${input('Fade out (seconds)','fadeOut',s.fadeOut,{min:0,max:duration(),step:.1})}</div><p class="helper">Gain above 100%, normalization, and fades are heard in the exported file. The live preview plays the source sound.</p>`:'<p class="helper">This source has no audio stream. Adding music or voice-over is a later multi-source editing feature.</p>'}`;
    }
  }
  document.addEventListener('input',e=>{
    if(!state.source||!['trimStart','trimEnd'].includes(e.target.dataset.setting))return;
    media.pause();media.currentTime=Number(e.target.value);updatePlayhead();
  });
  document.addEventListener('change',e=>{
    const el=e.target.closest('[data-setting]');if(!el||!state.settings)return;
    const key=el.dataset.setting;let value=el.type==='checkbox'?el.checked:el.value;
    const numeric=['crf','videoBitrate','audioBitrate','audioTrack','speed','rotation','volume','fadeIn','fadeOut','start','end','trimStart','trimEnd'];
    if(numeric.includes(key)||key.startsWith('crop.')){value=Number(value);if(!Number.isFinite(value)){toast('Enter a valid number.');render();return;}}
    if(['start','end','trimStart','trimEnd'].includes(key)){
      setTrim(key==='start'||key==='trimStart'?'start':'end',value);return;
    }
    if(key.startsWith('crop.')){change({crop:{...state.settings.crop,[key.split('.')[1]]:value}});return;}
    change({[key]:value});
  });
  function applyCrop(ratio){
    const crop={left:0,right:0,top:0,bottom:0};
    if(ratio!=='original'){
      let target=Number(ratio);if(state.settings.rotation===90||state.settings.rotation===270)target=1/target;
      const natural=state.source.width/state.source.height;
      if(natural>target)crop.left=crop.right=Number(((1-target/natural)*50).toFixed(6));
      else crop.top=crop.bottom=Number(((1-natural/target)*50).toFixed(6));
    }
    change({crop});
  }
  function moveClip(direction){const from=state.selected,to=from+direction,clips=copy(state.settings.segments);if(to<0||to>=clips.length)return;[clips[from],clips[to]]=[clips[to],clips[from]];state.selected=to;change({segments:clips});media.currentTime=clips[to].start;}
  $('#split').onclick=()=>{
    const clips=copy(state.settings.segments),r=clips[state.selected],t=Number(media.currentTime.toFixed(2));
    if(!r||t-r.start<.04||r.end-t<.04){toast('Place the playhead inside the selected kept clip, away from its edges.');return;}
    if(clips.length>=24){toast('This workbench supports up to 24 kept clips.');return;}
    clips.splice(state.selected,1,{start:r.start,end:t},{start:t,end:r.end});state.selected++;change({segments:clips});
  };
  $('#remove-clip').onclick=()=>{if(state.settings.segments.length<2)return;const clips=copy(state.settings.segments);clips.splice(state.selected,1);state.selected=Math.max(0,state.selected-1);change({segments:clips});media.currentTime=clips[state.selected].start;};
  $('#reset-cuts').onclick=()=>{state.selected=0;change({segments:[{start:0,end:state.source.duration}]});media.currentTime=0;};
  function draw(){
    if(state.view!=='edit'||!state.source?.hasVideo||media.readyState<2||!media.videoWidth)return;
    const s=state.settings,src=state.source,c=s.crop;const even=n=>Math.max(2,Math.floor(n/2)*2);
    const sw=even(src.width||media.videoWidth),sh=even(src.height||media.videoHeight);
    const x=Math.floor(sw*c.left/100/2)*2,y=Math.floor(sh*c.top/100/2)*2;
    const cw=Math.min(sw-x,even(sw*(1-(c.left+c.right)/100))),ch=Math.min(sh-y,even(sh*(1-(c.top+c.bottom)/100)));
    if(cw<=0||ch<=0)return;
    const rotated=s.rotation===90||s.rotation===270,ow=rotated?ch:cw,oh=rotated?cw:ch;
    const factor=Math.min(1,900/Math.max(ow,oh)),canvas=$('#preview-canvas');
    canvas.width=Math.round(ow*factor);canvas.height=Math.round(oh*factor);
    const ctx=canvas.getContext('2d');ctx.translate(canvas.width/2,canvas.height/2);ctx.scale(factor,factor);
    if(s.flip)ctx.scale(-1,1);ctx.rotate(s.rotation*Math.PI/180);
    try{ctx.drawImage(media,x*media.videoWidth/sw,y*media.videoHeight/sh,cw*media.videoWidth/sw,ch*media.videoHeight/sh,-cw/2,-ch/2,cw,ch);}
    catch{ /* Preview decoding can briefly lag a seek. The next frame retries. */ }
  }
  function updatePlayhead(){
    $('#play-time').textContent=time(media.currentTime,true);$('#scrub').value=media.currentTime;
    if(!media.paused&&state.settings?.segments.length){
      const clips=state.settings.segments,r=clips[playIndex];
      if(r&&media.currentTime>=r.end-.025){
        if($('#preview-scope').value==='all'&&playIndex+1<clips.length){playIndex++;media.currentTime=clips[playIndex].start;}
        else{media.pause();media.currentTime=r.end;}
      }
    }
    window.FormaTimeline?.playhead();draw();
  }
  $('#play').onclick=()=>{
    if(!state.source?.duration){toast('This browser cannot preview the source. Use the local runner to inspect it.');return;}
    if(!media.paused){media.pause();return;}
    playIndex=$('#preview-scope').value==='clip'?state.selected:0;const r=state.settings.segments[playIndex];
    if(media.currentTime<r.start||media.currentTime>=r.end)media.currentTime=r.start;
    media.play().catch(()=>toast('This browser cannot decode the preview. FFmpeg export is independent of browser playback.'));
  };
  $('#preview-scope').onchange=()=>media.pause();
  $('#scrub').oninput=e=>{media.pause();media.currentTime=Number(e.target.value);updatePlayhead();};
  media.addEventListener('loadeddata',()=>{$('#preview-error').hidden=true;
    // A hidden decoder may not expose its first bitmap until an explicit seek.
    if(media.currentTime===0&&Number.isFinite(media.duration))media.currentTime=Math.min(.001,media.duration/2);
    draw();});media.addEventListener('seeked',draw);media.addEventListener('timeupdate',updatePlayhead);
  media.addEventListener('error',()=>{if(state.source){$('#preview-error').hidden=false;$('#preview-canvas').hidden=true;}});
  media.addEventListener('play',()=>{$('#play').innerHTML=icon('pause');$('#play').setAttribute('aria-label','Pause preview');const tick=()=>{if(media.paused)return;updatePlayhead();requestAnimationFrame(tick);};requestAnimationFrame(tick);});
  media.addEventListener('pause',()=>{$('#play').innerHTML=icon('play');$('#play').setAttribute('aria-label','Play kept clips');});
  document.addEventListener('keydown',e=>{
    const tab=e.target.closest('[role=tab]');
    if(tab&&['ArrowLeft','ArrowRight','Home','End'].includes(e.key)){
      const tabs=$$('button[role=tab]:not(:disabled)',tab.closest('[role=tablist]'));
      let index=tabs.indexOf(tab);index=e.key==='Home'?0:e.key==='End'?tabs.length-1:(index+(e.key==='ArrowRight'?1:-1)+tabs.length)%tabs.length;
      e.preventDefault();tabs[index].click();tabs[index].focus();return;
    }
    if(/INPUT|SELECT|TEXTAREA|BUTTON|A/.test(e.target.tagName)||e.target.isContentEditable)return;
    if(state.view==='edit'&&state.page==='workspace'&&e.code==='Space'){e.preventDefault();$('#play').click();}
    if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='z'&&state.source){e.preventDefault();history(!e.shiftKey);}
  });
  function schedulePlan(){
    clearTimeout(planTimer);const serial=++planSequence;state.plan=null;state.planPending=true;renderFooter();
    if(!state.source?.backend||!state.engine.available){state.planPending=false;renderFooter();return;}
    const sourceId=state.source.id,settings=copy(state.settings);
    planTimer=setTimeout(async()=>{try{const p=await api('/api/plan',{sourceId,settings});if(serial!==planSequence)return;state.plan=p;state.planError='';}
      catch(e){if(serial!==planSequence)return;state.planError=e.message;}
      finally{if(serial===planSequence){state.planPending=false;renderFooter();}}},180);
  }
  async function enqueue(showQueue){
    if(!state.engine.available||!state.source?.backend){help();return;}
    if(!state.plan||state.planError||state.planPending){toast('Resolve the export settings before converting.');return;}
    const snapshot=copy(state.settings),sourceId=state.source.id;
    $('#convert').disabled=true;$('#add-queue').disabled=true;
    try{await api('/api/jobs',{sourceId,settings:snapshot});await refreshQueue();if(showQueue)navigate('queue');else toast('Added to the conversion queue. The queued settings are now fixed.');}
    catch(e){toast(e.message);}finally{renderFooter();}
  }
  $('#convert').onclick=()=>enqueue(true);$('#add-queue').onclick=()=>enqueue(false);
  async function refreshQueue(){state.jobs=await api('/api/jobs');$('#queue-count').textContent=state.jobs.length;renderQueue();}
  async function pollQueue(){try{await refreshQueue();}catch{ /* A stopped runner never manufactures a successful job. */ }setTimeout(pollQueue,900);}
  function renderQueue(){
    if(!state.jobs.length){$('#queue-list').innerHTML='<div class="queue-empty">No exports yet.<br><br>Add media, choose a result, and convert.</div>';return;}
    $('#queue-list').innerHTML=[...state.jobs].reverse().map(j=>`<article class="queue-item" data-job="${esc(j.id)}"><div class="job-top"><div class="job-icon">${icon(j.mode==='video'?'video':'audio')}</div><div class="job-info"><b>${esc(j.outputName)}</b><span>${esc(j.sourceName)} · ${time(j.duration)}${j.size?' · '+size(j.size):''}</span></div><span class="job-status ${esc(j.status)}">${esc(j.status)}</span></div>${j.status==='encoding'||j.status==='verifying'?`<div class="progress" role="progressbar" aria-label="Encoding progress" aria-valuenow="${Math.round(j.progress*100)}" aria-valuemin="0" aria-valuemax="100"><span style="width:${Math.round(j.progress*100)}%"></span></div>`:''}${j.error?`<pre class="job-error">${esc(j.error)}</pre>`:''}<div class="job-actions">${['encoding','queued','verifying'].includes(j.status)?`<button data-cancel="${esc(j.id)}" class="small-button">Cancel</button>`:''}${j.status==='completed'?`<span class="pill">VERIFIED OUTPUT</span><a href="/output/${encodeURIComponent(j.id)}" target="_blank" rel="noopener" class="small-button">Open</a><a href="/output/${encodeURIComponent(j.id)}?download=1" class="small-button" download="${esc(j.outputName)}">${icon('download')}Save file</a>`:''}</div></article>`).join('');
  }
  window.FormaStudio={state,activate,defaults,change,navigate,setView,render,draw,duration,applyCrop,setTrim,clampTrim,updatePlayhead,time};
  const measureFooter=()=>{
    const h=$('#export-bar').hidden?0:Math.ceil($('#export-bar').getBoundingClientRect().height);
    document.documentElement.style.setProperty('--export-height',`${h}px`);
  };
  new ResizeObserver(measureFooter).observe($('#export-bar'));
  if(window.visualViewport){
    const keyboardLayout=()=>{
      const focused=/INPUT|TEXTAREA|SELECT/.test(document.activeElement?.tagName||'');
      const keyboard=focused&&window.visualViewport.scale===1&&innerHeight-window.visualViewport.height>120;
      document.body.classList.toggle('keyboard-open',keyboard);
      if(keyboard)document.activeElement.scrollIntoView({block:'center',behavior:'instant'});
    };
    window.visualViewport.addEventListener('resize',keyboardLayout);
    document.addEventListener('focusout',()=>setTimeout(keyboardLayout,0));
  }
  connect();
})();
