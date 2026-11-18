from matrix_ui import *
if len(sys.argv)!=2:raise SystemExit('Usage: jump_menu.py UNIQUE_RUN_NAME')
for i in range(1,4):
 case='jump-menu-'+sys.argv[1]+'-'+str(i);t=launch(case);ns=options();tapnode(next(n for n in ns if n.get('text')=='Ir al tiempo'))
 ns=nodes();four=next(n for n in ns if n.get('resource-id','').endswith('/tim_pic_4'));ok=next(n for n in ns if n.get('resource-id','').endswith('/tim_pic_ok'))
 tapnode(four);time.sleep(.2);tapnode(four);log(case,'requested_seek_via_time_dialog',seconds=44);tapnode(ok);time.sleep(.3);state(case,'after_time_dialog_seek');time.sleep(3)
 log(case,'results_after_action',results=result(t))
 if not result(t):back()
 events()
