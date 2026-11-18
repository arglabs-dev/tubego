from driver import *
if len(sys.argv)!=2:raise SystemExit('Usage: long_resume.py UNIQUE_RUN_NAME')
saved=932000
for i in range(1,4):
 case='long-resume-15m-'+sys.argv[1]+'-'+str(i);t=launch(case,saved,long_clip=True);time.sleep(8)
 sh('cmd','media_session','dispatch','pause');time.sleep(.5);state(case,'paused_around_15m');back()
 r=result(t);log(case,'results_after_action',results=r)
 if len(r)!=1 or not r[0]['detail']['accepted_checkpoint']:raise RuntimeError('Expected one valid long checkpoint')
 saved=r[0]['detail']['extra_position']
