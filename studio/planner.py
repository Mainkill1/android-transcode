"""Pure, validated single-source FFmpeg job planning. No shell interpolation."""
from __future__ import annotations
import math
import re
from pathlib import Path
from typing import Any

VIDEO_ENCODERS = {'h264':'libx264','hevc':'libx265','vp9':'libvpx-vp9','av1':'libaom-av1'}
VIDEO_FORMATS = {'mp4':['h264','hevc','av1'], 'mkv':['h264','hevc','vp9','av1'],
                 'mov':['h264','hevc'], 'webm':['vp9','av1']}
AUDIO_FORMATS = {'mp3':'libmp3lame','m4a':'aac','flac':'flac','wav':'pcm_s16le','opus':'libopus'}
SAFE_DEMUXERS = 'mov,matroska,webm,avi,mpegts,mp3,wav,flac,ogg,aac,mpeg,aiff,asf'

def default_settings(source: dict | None = None) -> dict:
    audio = source is not None and not source.get('hasVideo',False)
    return dict(mode='audio' if audio else 'video',format='mp3' if audio else 'mp4',
        videoCodec='h264',rateMode='quality',crf=23,videoBitrate=2500,preset='fast',
        resolution='source',fps='source',audioBitrate=192,audioTrack=0,channels='source',
        sampleRate='source',segments=[],crop=dict(left=0,right=0,top=0,bottom=0),
        rotation=0,flip=False,speed=1.0,volume=1.0,mute=False,normalize=False,
        fadeIn=0.0,fadeOut=0.0,stripMetadata=True,filename='')

def number(value: Any, label: str, lo: float, hi: float, integer: bool = False) -> float | int:
    try:
        if isinstance(value,bool): raise ValueError()
        n=float(value)
    except (TypeError,ValueError): raise ValueError(f'{label} must be a number.') from None
    if not math.isfinite(n) or not lo<=n<=hi or (integer and n!=int(n)):
        raise ValueError(f'{label} must be between {lo:g} and {hi:g}'+(' (whole numbers).' if integer else '.'))
    return int(n) if integer else n

def choice(value: Any, label: str, options) -> Any:
    if value not in options: raise ValueError(f'Unsupported {label}.')
    return value

def num(n: float) -> str: return f'{n:.6f}'.rstrip('0').rstrip('.') if n else '0'
def even(n: float) -> int: return max(2,int(n)//2*2)

def safe_name(name: str, extension: str) -> str:
    name=str(name).strip()
    name=re.sub(r'\.[A-Za-z0-9]{1,5}$','',name)
    name=re.sub(r'[<>:"/\\|?*\x00-\x1f]','_',name).strip('. ')[:110] or 'converted'
    if re.fullmatch(r'(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])',name): name='media-'+name
    return name+'.'+extension

def make_plan(source: dict, settings: dict, encoders: set[str] | None = None) -> dict:
    if not isinstance(settings,dict): raise ValueError('Settings must be an object.')
    s=default_settings(source);s.update(settings)
    mode=choice(s['mode'],'output mode',('video','audio'))
    video=mode=='video'
    if not video:
        # A hidden, invalid video control must not prevent an audio-only export.
        # Normalize only the execution copy; the UI retains its video draft.
        base=default_settings()
        for key in ('videoCodec','rateMode','crf','videoBitrate','preset','resolution','fps','crop','rotation','flip'):
            s[key]=base[key]
    if video and not source.get('hasVideo'): raise ValueError('This source does not contain video.')
    if video and source.get('hdr'): raise ValueError('HDR video needs a qualified color-managed export path; this runner only exports SDR video.')
    duration=number(source.get('duration'),'Source duration',.01,86400*7)
    fmt=choice(s['format'],'format',VIDEO_FORMATS if video else AUDIO_FORMATS)
    codec=choice(s['videoCodec'],'video codec',VIDEO_ENCODERS)
    if video and codec not in VIDEO_FORMATS[fmt]: raise ValueError('Select a compatible video codec and container.')
    choice(s['rateMode'],'rate control',('quality','bitrate'))
    choice(s['preset'],'encoder preset',('ultrafast','fast','medium','slow'))
    crf=number(s['crf'],'Video quality',0,51 if codec in ('h264','hevc') else 63,True)
    bitrate=number(s['videoBitrate'],'Video bitrate',100,200000,True)
    abitrate=number(s['audioBitrate'],'Audio bitrate',16,320,True)
    resolution=choice(str(s['resolution']),'resolution',('source','480','720','1080','2160'))
    fps=choice(str(s['fps']),'frame rate',('source','24','25','30','50','60'))
    channels=choice(str(s['channels']),'audio channels',('source','1','2'))
    samplerate=choice(str(s['sampleRate']),'sample rate',('source','22050','32000','44100','48000','96000'))
    speed=number(s['speed'],'Playback speed',.5,2)
    volume=number(s['volume'],'Volume',0,3)
    rotation=choice(s['rotation'],'rotation',(0,90,180,270))
    for key in ('flip','mute','normalize','stripMetadata'):
        if not isinstance(s[key],bool): raise ValueError(f'{key} must be true or false.')
    if not video and s['mute']: raise ValueError('Audio output cannot be muted. Adjust volume instead.')
    tracks=source.get('audioTracks',[])
    audio=bool(source.get('hasAudio') and tracks and not s['mute'])
    if not video and not audio: raise ValueError('This source does not contain an audio track.')
    trackindex=number(s['audioTrack'],'Audio track',0,max(0,len(tracks)-1),True)
    aencoder=(('libopus' if fmt=='webm' else 'aac') if video else AUDIO_FORMATS[fmt]) if audio else None
    vencoder=VIDEO_ENCODERS[codec] if video else None
    if aencoder=='libmp3lame' and abitrate not in (16,24,32,40,48,56,64,80,96,112,128,160,192,224,256,320):
        raise ValueError('Choose a supported MP3 bitrate (for example 128, 192 or 320).')
    for enc in (vencoder,aencoder):
        if enc and encoders is not None and enc not in encoders: raise ValueError(f'The installed FFmpeg build does not include encoder {enc}.')
    segments=s['segments'] or [dict(start=0,end=duration)]
    if not isinstance(segments,list) or not 1<=len(segments)<=24: raise ValueError('Use between 1 and 24 kept ranges.')
    normalized=[]
    for segment in segments:
        if not isinstance(segment,dict): raise ValueError('A kept range must have a start and end.')
        start=number(segment.get('start'),'Trim range start',0,duration)
        end=number(segment.get('end'),'Trim range end',0,duration)
        if end-start<.04: raise ValueError('Every kept range must end after its start (at least 0.04 seconds).')
        normalized.append(dict(start=start,end=end))
    outduration=sum(r['end']-r['start'] for r in normalized)/speed
    fadein=number(s['fadeIn'],'Audio fade in',0,outduration)
    fadeout=number(s['fadeOut'],'Audio fade out',0,outduration)
    if fadein+fadeout>outduration: raise ValueError('Audio fades cannot exceed the output duration.')
    crop=s['crop']
    if not isinstance(crop,dict): raise ValueError('The crop must be an object.')
    crop={k:number(crop.get(k,0),'Picture crop',0,95) for k in ('left','right','top','bottom')}
    if crop['left']+crop['right']>=95 or crop['top']+crop['bottom']>=95:
        raise ValueError('The crop removes too much of the picture.')
    width=height=0
    if video:
        sw=even(number(source.get('width'),'Source width',2,32768));sh=even(number(source.get('height'),'Source height',2,32768))
        x=int(sw*crop['left']/100)//2*2;y=int(sh*crop['top']/100)//2*2
        cw=even(sw*(1-(crop['left']+crop['right'])/100));ch=even(sh*(1-(crop['top']+crop['bottom'])/100))
        cw=min(cw,sw-x);ch=min(ch,sh-y)
        width,height=(ch,cw) if rotation in (90,270) else (cw,ch)
        if resolution!='source':
            short=int(resolution);long={480:854,720:1280,1080:1920,2160:3840}[short]
            mw,mh=(long,short) if width>=height else (short,long)
            factor=min(1,mw/width,mh/height);width=even(width*factor);height=even(height*factor)
    graph=[];count=len(normalized)
    vi=int(source.get('videoIndex',0));ai=int(tracks[trackindex]['index']) if audio else 0
    if video and count>1:graph.append(f'[0:{vi}]split={count}'+''.join(f'[vs{i}]' for i in range(count)))
    if audio and count>1:graph.append(f'[0:{ai}]asplit={count}'+''.join(f'[as{i}]' for i in range(count)))
    for i,r in enumerate(normalized):
        if video:
            vf=[f'trim=start={num(r["start"])}:end={num(r["end"])}','setpts=PTS-STARTPTS',f'scale={sw}:{sh}','setsar=1']
            if any(crop.values()):vf.append(f'crop={cw}:{ch}:{x}:{y}')
            vf += {0:[],90:['transpose=clock'],180:['hflip','vflip'],270:['transpose=cclock']}[rotation]
            if s['flip']:vf.append('hflip')
            if speed!=1:vf.append(f'setpts=PTS/{num(speed)}')
            vf.extend([f'scale={width}:{height}','setsar=1','format=yuv420p'])
            graph.append((f'[vs{i}]' if count>1 else f'[0:{vi}]')+','.join(vf)+f'[v{i}]')
        if audio:
            af=[f'atrim=start={num(r["start"])}:end={num(r["end"])}','asetpts=PTS-STARTPTS']
            if speed!=1:af.append(f'atempo={num(speed)}')
            af+=['apad',f'atrim=duration={num((r["end"]-r["start"])/speed)}','asetpts=PTS-STARTPTS']
            graph.append((f'[as{i}]' if count>1 else f'[0:{ai}]')+','.join(af)+f'[a{i}]')
    if count>1:
        joined=''.join((f'[v{i}]' if video else '')+(f'[a{i}]' if audio else '') for i in range(count))
        graph.append(joined+f'concat=n={count}:v={int(video)}:a={int(audio)}'+('[vjoined]' if video else '')+('[ajoined]' if audio else ''))
    if video:graph.append(('[vjoined]' if count>1 else '[v0]')+('null' if fps=='source' else f'fps={fps}')+'[vout]')
    if audio:
        effects=[]
        if volume!=1:effects.append(f'volume={num(volume)}')
        if s['normalize']:effects.append('loudnorm=I=-16:TP=-1.5:LRA=11')
        if fadein:effects.append(f'afade=t=in:st=0:d={num(fadein)}')
        if fadeout:effects.append(f'afade=t=out:st={num(outduration-fadeout)}:d={num(fadeout)}')
        graph.append(('[ajoined]' if count>1 else '[a0]')+','.join(effects or ['anull'])+'[aout]')
    s.update(segments=normalized,crop=crop,speed=speed,volume=volume,crf=crf,videoBitrate=bitrate,
             audioBitrate=abitrate,channels=channels,sampleRate=samplerate,fps=fps,resolution=resolution)
    sample=48000 if aencoder=='libopus' else (int(samplerate) if samplerate!='source' else int(tracks[trackindex].get('sampleRate') or 48000) if audio else 48000)
    if aencoder=='libmp3lame' and sample>48000:sample=48000
    output=safe_name(s.get('filename') or re.sub(r'\.[^.]+$','',source.get('name','media'))+'-converted',fmt)
    return dict(settings=s,filtergraph=';'.join(graph),videoEncoder=vencoder,audioEncoder=aencoder,
                extension=fmt,outputName=output,duration=outduration,width=width,height=height,sampleRate=sample)

def build_command(plan: dict,input_path: str,output_path: str,ffmpeg: str='ffmpeg') -> list[str]:
    if Path(input_path).resolve()==Path(output_path).resolve(): raise ValueError('Output must never overwrite the source.')
    s=plan['settings']
    args=[ffmpeg,'-hide_banner','-loglevel','error','-nostdin','-y','-protocol_whitelist','file,pipe',
          '-format_whitelist',SAFE_DEMUXERS,'-i',str(input_path),'-filter_complex_threads','2',
          '-filter_complex',plan['filtergraph']]
    ve=plan['videoEncoder'];ae=plan['audioEncoder']
    if ve:
        args+=['-map','[vout]','-c:v',ve,'-pix_fmt','yuv420p','-threads','2']
        if ve in ('libx264','libx265'):args+=['-preset',s['preset']]
        if ve=='libx265':args+=['-x265-params','pools=2:frame-threads=2:log-level=error']
        if ve=='libvpx-vp9':args+=['-deadline','good','-cpu-used','4','-row-mt','1']
        if ve=='libaom-av1':args+=['-cpu-used','6','-row-mt','1']
        if s['rateMode']=='quality':
            args+=['-crf',str(s['crf'])]
            if ve in ('libvpx-vp9','libaom-av1'):args+=['-b:v','0']
        else:args+=['-b:v',str(s['videoBitrate'])+'k']
        if ve=='libx265' and plan['extension'] in ('mp4','mov'):args+=['-tag:v','hvc1']
    else:args+=['-vn']
    if ae:
        args+=['-map','[aout]','-c:a',ae,'-ar',str(plan['sampleRate'])]
        if ae not in ('flac','pcm_s16le'):args+=['-b:a',str(s['audioBitrate'])+'k']
        if s['channels']!='source':args+=['-ac',s['channels']]
    else:args+=['-an']
    args+=['-sn','-dn','-map_chapters','-1','-map_metadata','-1' if s['stripMetadata'] else '0']
    if plan['extension'] in ('mp4','mov','m4a'):args+=['-movflags','+faststart']
    args+=['-max_muxing_queue_size','4096','-t',num(plan['duration']),'-progress','pipe:1','-nostats',str(output_path)]
    return args
