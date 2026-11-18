from driver import *
import xml.etree.ElementTree as E

def nodes():
 sh('uiautomator','dump','/sdcard/pla257-window.xml');return list(E.fromstring(adb('exec-out','cat','/sdcard/pla257-window.xml')).iter('node'))
def tapnode(n):
 x,y,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')));sh('input','tap',str((x+x2)//2),str((y+y2)//2))
def options():
 sh('input','keyevent','82');time.sleep(.3)
 return nodes()
def runmore(case,mode):
 t=launch(case)
 if mode=='audio':
  ns=options();target=[n for n in ns if n.get('text')=='Reproducir como audio']
  if not target:raise RuntimeError('Audio menu not found')
  tapnode(target[0]);time.sleep(2);state(case,'converted_audio');log(case,'results_at_conversion',results=result(t));time.sleep(32);state(case,'after_audio_expected_end')
 elif mode=='recents':
  sh('input','keyevent','187');time.sleep(1)
  ns=nodes();labels=[dict(text=n.get('text'),description=n.get('content-desc'),bounds=n.get('bounds')) for n in ns if 'VLC' in n.get('text','') or 'VLC' in n.get('content-desc','')]
  log(case,'recents_vlc_card',labels=labels)
  if not labels:raise RuntimeError('VLC card not identified; no swipe')
  sh('input','swipe','540','1300','540','150','500');time.sleep(2);state(case,'after_swipe')
 time.sleep(.4);r=result(t);log(case,'results_after_action',results=r)
 if not r:
  if mode=='audio':sh('cmd','media_session','dispatch','stop');time.sleep(.5)
  else:back()
  if not result(t):sh('am','start','--activity-single-top','-a',P+'.ABANDON','-n',C)
 events()
if __name__=='__main__':
 if len(sys.argv)!=3 or sys.argv[1] not in ('audio','recents'):
  raise SystemExit('Usage: matrix_ui.py audio|recents UNIQUE_RUN_NAME')
 for i in range(1,4):runmore(f'{sys.argv[1]}-{sys.argv[2]}-{i}',sys.argv[1])
