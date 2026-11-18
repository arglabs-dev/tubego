import subprocess,time,json,re,sys
from pathlib import Path
S='R4HAEIVC9DQKXCDI';P='dev.arglabs.tubego.vlcprobe';C=P+'/dev.arglabs.tubego.spike.ProbeActivity';D=Path(__file__).resolve().parent
def adb(*a):return subprocess.check_output(['adb','-s',S,*a],text=True)
def sh(*a):return adb('shell',*a)
def log(case,event,**kw):
 row=dict(case=case,event=event,utc=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),**kw)
 with (D/'observations.jsonl').open('a') as f:f.write(json.dumps(row)+'\n')
 print(row,flush=True)
def events():
 raw=adb('exec-out','run-as',P,'cat','files/evidence.jsonl')
 previous=(D/'evidence.jsonl').read_text() if (D/'evidence.jsonl').exists() else ''
 if not raw.startswith(previous):raise RuntimeError('Device evidence diverged; refusing to overwrite local evidence')
 (D/'evidence.jsonl').write_text(raw)
 return [json.loads(l) for l in raw.splitlines()]
def result(trial):return [e for e in events() if e['trial']==trial and e['event']=='activity_result']
def state(case,event):
 raw=sh('dumpsys','media_session');part=raw.split('VLC org.videolan.vlc/VLC (userId=0)')[-1].split('queueTitle=')[0] if 'VLC org.videolan.vlc/VLC (userId=0)' in raw else ''
 states=[l.strip() for l in part.splitlines() if 'state=PlaybackState' in l]
 activity=[l.strip() for l in sh('dumpsys','activity','activities').splitlines() if 'topResumedActivity=' in l or 'mResumedActivity:' in l or 'mode=pinned' in l and 'org.videolan.vlc' in l]
 log(case,event,vlc_states=states,activities=activity)
def back():
 sh('input','keyevent','4');time.sleep(.3)
 focus=sh('dumpsys','window');m=re.search(r'mCurrentFocus=(.*)',focus)
 if m and 'org.videolan.vlc' in m[1]:sh('input','keyevent','4')
 time.sleep(.5)
def launch(case,pos=20000,rewind=10,caller_event=None,long_clip=False):
 if any(e['event']=='launch' and e['detail']['scenario']==case for e in events()):raise RuntimeError('Use a new scenario/command ID for each run')
 sh('am','start','--activity-single-top','-a',P+'.RUN','-n',C,'--es','scenario',case,'--es','command_id',case,'--el','saved_ms',str(pos),'--ei','rewind_s',str(rewind),'--ez','long_clip',str(long_clip).lower(),*(['--es','caller_event',caller_event] if caller_event else []));time.sleep(2)
 ev=events();launches=[e for e in ev if e['event']=='launch' and e['detail']['scenario']==case]
 if not launches:raise RuntimeError('Launch absent; foreground or pending trial requires inspection')
 trial=launches[-1]['trial'];state(case,'started');return trial
def run(case,mode):
 if mode=='cold':sh('am','force-stop','org.videolan.vlc')
 if mode=='warm':sh('am','start','-n','org.videolan.vlc/.StartActivity');time.sleep(.5)
 t=launch(case,5000 if mode=='zero' else 43000 if mode=='natural' else 20000,caller_event='die' if mode=='caller_kill' else 'recreate' if mode=='recreate' else None)
 if mode in ('cold','warm','zero'):
  time.sleep(3);sh('cmd','media_session','dispatch','pause');time.sleep(.5);state(case,'paused');back()
 elif mode=='force_stop':sh('am','force-stop','org.videolan.vlc');time.sleep(1)
 elif mode=='natural':time.sleep(16);state(case,'after_expected_end')
 elif mode=='home':
  sh('input','keyevent','3');time.sleep(3);state(case,'home_pip');log(case,'callbacks_while_pip',results=result(t));sh('am','start','-n','org.videolan.vlc/org.videolan.vlc.gui.video.VideoPlayerActivity');time.sleep(.5);back()
 elif mode in ('caller_kill','recreate'):
  before=subprocess.run(['adb','-s',S,'shell','pidof',P],capture_output=True,text=True).stdout.strip();time.sleep(3);after=subprocess.run(['adb','-s',S,'shell','pidof',P],capture_output=True,text=True).stdout.strip();log(case,'caller_process',before=before,after=after);back()
 time.sleep(.5);r=result(t);log(case,'results_after_action',results=r)
 if not r:
  # Do not start the next trial atop a running VLC instance.
  back();r=result(t);log(case,'results_after_cleanup_back',results=r)
  if not r:sh('am','start','--activity-single-top','-a',P+'.ABANDON','-n',C);events()
if __name__=='__main__':
 if len(sys.argv)!=3 or sys.argv[1] not in ('cold','warm','zero','natural','home','caller_kill','recreate','force_stop'):
  raise SystemExit('Usage: driver.py MODE UNIQUE_RUN_NAME')
 for i in range(1,4):run(f'{sys.argv[1]}-{sys.argv[2]}-{i}',sys.argv[1])
