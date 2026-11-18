package dev.arglabs.tubego;

import android.app.Activity;
import android.content.*;
import android.media.AudioManager;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.Locale;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.videolan.libvlc.*;
import org.videolan.libvlc.util.VLCVideoLayout;

/** Own playback UI and gestures; no code from VLC's GPL application interface. */
public final class PlayerActivity extends LocalizedActivity {
    private final ExecutorService disk=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private String origin,id,token;private Uri uri;private ParcelFileDescriptor descriptor;
    private PlayerGestures gestureController;private float heldRate=-1;private MediaPlayer.ScaleType beforePinch;
    private LibVLC engine;private MediaPlayer player;private PlaybackTimeline timeline;
    private VLCVideoLayout video;private PlayerControls controls;private android.media.session.MediaSession mediaSession;
    private boolean isSeen,orientationLocked,repeat;private float zoom=1;private int aspectIndex;private long repeatStart=-1,repeatEnd=-1,sleepAt;
    private String displayTitle="";
    private final android.content.SharedPreferences preferences(){return getSharedPreferences("player_controls",MODE_PRIVATE);}
    private boolean resumed,prepared,initialSeek,dragging,done;private long startAt,lastSaved;
    private float brightness;private int volume;private AudioManager audio;private android.media.AudioFocusRequest focus;
    @Override public void setContentView(View view){getWindow().setContentView(view);}
    private String t(String text){return Texts.text(this,text);}
    private final Runnable tick=new Runnable(){@Override public void run(){
        if(isDestroyed())return;
        try {
            if(player!=null){synchronized(SessionStore.class){PlaybackProgress.session(PlayerActivity.this,origin,token);}
                sample();render();
                if(repeatEnd>repeatStart&&repeatStart>=0&&timeline.position>=repeatEnd)seekTo(repeatStart);
                if(sleepAt>0&&SystemClock.elapsedRealtime()>=sleepAt){sleepAt=0;pausePlayback(true);notice(t("Temporizador finalizado"));}
                if(SystemClock.elapsedRealtime()-lastSaved>=5000)persist(false);}
        }catch(Exception revoked){if(player!=null)player.pause();notice(t("Sesión no disponible."));finish();return;}
        ui.postDelayed(this,500);
    }};
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);origin=getIntent().getStringExtra("server_url");id=getIntent().getStringExtra("resource_id");
        build();audio=getSystemService(AudioManager.class);setVolumeControlStream(AudioManager.STREAM_MUSIC);
        focus=new android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setWillPauseWhenDucked(true).setOnAudioFocusChangeListener(change->{if(change<0&&player!=null&&player.isPlaying()){sample();player.pause();timeline.discontinuity();persist(false);}},ui).build();
        mediaSession=new android.media.session.MediaSession(this,"TubeGo");
        mediaSession.setCallback(new android.media.session.MediaSession.Callback(){
            @Override public void onPlay(){if(player!=null){if(done)togglePlayback();else playWithFocus();}}
            @Override public void onPause(){pausePlayback(false);}
            @Override public void onSeekTo(long value){seekTo(value);}
            @Override public void onFastForward(){if(timeline!=null)seekTo(timeline.position+10000);}
            @Override public void onRewind(){if(timeline!=null)seekTo(timeline.position-10000);}
        },ui);
        disk.execute(()->{
            ParcelFileDescriptor fd=null;
            try {
                synchronized(SessionStore.class){JSONObject session=new SessionStore(this,origin).read();if(session==null)throw new SecurityException();token=session.getString("token");PlaybackProgress.session(this,origin,token);}
                JSONObject saved=PlaybackProgress.read(this,origin,token,id);
                synchronized(SessionStore.class){JSONObject current=PlaybackProgress.session(this,origin,token);displayTitle=OfflineMediaAccess.record(LinkOutboxDispatch.root(this,origin,current),id).title;}
                int seconds; synchronized(SessionStore.class){JSONObject s=PlaybackProgress.session(this,origin,token);seconds=new LocalMediaPreferences(LinkOutboxDispatch.root(this,origin,s)).read().optInt("rewind_seconds",10);}
                uri=PrivateMediaContentProvider.create(this,origin,id);fd=getContentResolver().openFileDescriptor(uri,"r");
                if(fd==null)throw new java.io.IOException();
                PlaybackTimeline restored=new PlaybackTimeline(saved.optLong("position_ms"),saved.optLong("duration_ms"),PlaybackState.ranges(saved),saved.optBoolean("ended"));
                HistoryOpened.record(this,origin,id,token);
                long target=PlaybackTimeline.resume(restored.position,seconds);final ParcelFileDescriptor opened=fd;fd=null;
                runOnUiThread(()->{if(isDestroyed()){try{opened.close();}catch(Exception ignored){}return;}descriptor=opened;controls.title(displayTitle);timeline=restored;startAt=target;prepared=true;updateSeen(saved);if(resumed)initialize();});
            }catch(Exception failed){if(fd!=null)try{fd.close();}catch(Exception ignored){}runOnUiThread(()->{controls.loading(false);notice(t("No se pudo abrir el archivo local: ")+Texts.error(this,failed));});}
        });
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void notice(String value){if(controls!=null)controls.notice(value);}
    private void build(){
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setNavigationBarContrastEnforced(false);getWindow().setStatusBarContrastEnforced(false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().getAttributes().layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(0xff000000);
        video=new VLCVideoLayout(this);root.addView(video,new FrameLayout.LayoutParams(-1,-1));
        controls=new PlayerControls(this,new PlayerControls.Actions(){
            public void toggle(){togglePlayback();}public void seek(long value){seekTo(value);}public void tracks(){showTracks();}
            public void orientation(){toggleOrientation();}public void aspect(){cycleAspect();}public void options(){showOptions();}
            public void back(){finish();}public void cast(){castScreen();}public void visible(boolean visible){systemBars(visible);}
        });
        controls.setSeekButtons(preferences().getBoolean("seek_buttons",false));root.addView(controls,new FrameLayout.LayoutParams(-1,-1));
        gestureController=new PlayerGestures(this,new PlayerGestures.Actions(){
            public long position(){return timeline==null?0:timeline.position;}public long duration(){return timeline==null?0:timeline.duration;}
            public boolean locked(){return controls.locked();}public boolean enabled(String key){return preferences().getBoolean(key,true);}
            public void tap(){controls.toggleVisible();}public void playPause(){togglePlayback();}public void unlock(){controls.showUnlock();}public void seek(long value){seekTo(value);}
            public void preview(long delta,long target){controls.show(false);controls.feedback(delta<0?"rewind":"forward",(delta<0?"−":"+")+PlayerControls.clock(Math.abs(delta)),PlayerControls.clock(target));}
            public void finishPreview(){controls.finishFeedback();}
            public void beginLevels(){
                brightness=getWindow().getAttributes().screenBrightness;
                if(brightness<0)try{brightness=android.provider.Settings.System.getInt(getContentResolver(),android.provider.Settings.System.SCREEN_BRIGHTNESS)/255f;}catch(Exception unavailable){brightness=.5f;}
                volume=audio==null?0:audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            }
            public void brightness(float dy,float height){WindowManager.LayoutParams params=getWindow().getAttributes();params.screenBrightness=PlayerGestureMath.brightness(brightness,dy,height);getWindow().setAttributes(params);controls.show(false);controls.feedback("brightness",Math.round(params.screenBrightness*100)+"%",t("Brillo"));}
            public void volume(float dy,float height){if(audio==null)return;int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);int value=PlayerGestureMath.volume(volume,dy,height,max);audio.setStreamVolume(AudioManager.STREAM_MUSIC,value,0);controls.show(false);controls.feedback("volume",(max==0?0:value*100/max)+"%",t("Volumen"));}
            public void zoom(boolean fill){if(player==null)return;if(fill){beforePinch=player.getVideoScale();player.setVideoScale(MediaPlayer.ScaleType.SURFACE_FIT_SCREEN);}else{player.setVideoScale(beforePinch==null?MediaPlayer.ScaleType.SURFACE_BEST_FIT:beforePinch);beforePinch=null;}controls.show(false);controls.feedback("aspect",t(fill?"Ajustar a pantalla":"Mejor ajuste"),"");controls.finishFeedback();}
            public void fast(boolean enabled){if(player==null||timeline==null)return;if(enabled&&player.isPlaying()){heldRate=player.getRate();sample();timeline.discontinuity();player.setRate(2);controls.show(false);controls.feedback("speed","2.00×",t("Velocidad de reproducción"));}else if(!enabled&&heldRate>0){sample();timeline.discontinuity();player.setRate(heldRate);heldRate=-1;controls.finishFeedback();}}
        });controls.gestures.setOnTouchListener(gestureController);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());controls.insets(bars.left,bars.top,bars.right,bars.bottom);}
            else controls.insets(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;
        });
        setContentView(root);systemBars(true);
    }
    private void systemBars(boolean visible){
        if(Build.VERSION.SDK_INT>=30){WindowInsetsController controller=getWindow().getInsetsController();if(controller!=null){controller.setSystemBarsAppearance(0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);if(visible)controller.show(WindowInsets.Type.systemBars());else controller.hide(WindowInsets.Type.systemBars());}}
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|(visible?0:View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY));
    }
    private void togglePlayback(){if(player==null)return;if(done){done=false;initialSeek=false;startAt=0;timeline.discontinuity();playWithFocus();}else if(player.isPlaying())pausePlayback(false);else playWithFocus();}
    private void pausePlayback(boolean wait){if(player!=null){sample();player.pause();timeline.discontinuity();persist(wait);}getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);if(audio!=null&&focus!=null)audio.abandonAudioFocusRequest(focus);render();}
    private void initialize(){
        if(player!=null||!prepared||isDestroyed())return;
        try {
            synchronized(SessionStore.class){PlaybackProgress.session(this,origin,token);}
            engine=new LibVLC(this,new java.util.ArrayList<>(java.util.Arrays.asList("--no-video-title-show")));
            player=new MediaPlayer(engine);player.attachViews(video,null,false,false);
            player.setEventListener(event->{
                if(isDestroyed())return;
                if(event.type==MediaPlayer.Event.Buffering)controls.loading(event.getBuffering()<100);
                if(event.type==MediaPlayer.Event.Playing){controls.loading(false);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);if(!initialSeek){initialSeek=true;timeline.seeking(startAt);player.setTime(startAt);}}
                else if(event.type==MediaPlayer.Event.EndReached){done=true;timeline.endReached();persist(false);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);audio.abandonAudioFocusRequest(focus);notice(t("Fin de reproducción"));if(repeat){done=false;initialSeek=false;startAt=0;playWithFocus();}}
                else if(event.type==MediaPlayer.Event.Paused)getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else if(event.type==MediaPlayer.Event.EncounteredError){controls.loading(false);persist(false);notice(t("No se pudo reproducir el archivo. Puedes abrirlo externamente."));}
                render();
            });
            Media media=new Media(engine,descriptor.getFileDescriptor());media.setHWDecoderEnabled(true,false);player.setMedia(media);media.release();playWithFocus();
        }catch(Exception failure){notice(t("No se pudo reproducir el archivo. Puedes abrirlo externamente."));}
    }
    private void playWithFocus(){if(audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED)player.play();else notice(t("No se pudo obtener el audio. Intenta reproducir otra vez."));}
    private void sample(){if(player!=null&&timeline!=null&&!done)timeline.sample(player.getTime(),player.getLength(),player.isPlaying(),SystemClock.elapsedRealtime(),player.getRate());}
    private void persist(boolean wait){
        if(timeline==null||timeline.duration<=0||timeline.isSeeking()||!initialSeek)return;
        PlaybackTimeline snapshot=new PlaybackTimeline(timeline.position,timeline.duration,timeline.ranges(),timeline.ended);lastSaved=SystemClock.elapsedRealtime();
        Future<?> saved=disk.submit(()->{try{PlaybackProgress.checkpoint(this,origin,token,id,snapshot);JSONObject savedState=PlaybackProgress.read(this,origin,token,id);runOnUiThread(()->{if(!isDestroyed())updateSeen(savedState);});}catch(Exception failure){runOnUiThread(()->notice(t("No se pudo guardar el progreso. Revisa la sesión y el espacio libre.")));}});
        if(wait)try{saved.get(1500,TimeUnit.MILLISECONDS);}catch(Exception delayed){/* Queued write still drains after Activity destruction. */}
    }
    private void seekTo(long value){if(player==null||timeline==null||timeline.duration<=0)return;sample();long target=Math.max(0,Math.min(timeline.duration-1,value));timeline.seeking(target);player.setTime(target);}
    private void render(){
        if(timeline==null||controls==null)return;controls.render(timeline.position,timeline.duration);controls.setPlaying(player!=null&&player.isPlaying());
        if(mediaSession!=null){long actions=android.media.session.PlaybackState.ACTION_PLAY|android.media.session.PlaybackState.ACTION_PAUSE|android.media.session.PlaybackState.ACTION_PLAY_PAUSE|android.media.session.PlaybackState.ACTION_SEEK_TO|android.media.session.PlaybackState.ACTION_REWIND|android.media.session.PlaybackState.ACTION_FAST_FORWARD;
            mediaSession.setPlaybackState(new android.media.session.PlaybackState.Builder().setActions(actions).setState(player!=null&&player.isPlaying()?android.media.session.PlaybackState.STATE_PLAYING:android.media.session.PlaybackState.STATE_PAUSED,timeline.position,player==null?1:player.getRate()).build());
            mediaSession.setMetadata(new android.media.MediaMetadata.Builder().putString(android.media.MediaMetadata.METADATA_KEY_TITLE,displayTitle).putLong(android.media.MediaMetadata.METADATA_KEY_DURATION,timeline.duration).build());mediaSession.setActive(resumed||isInPictureInPictureMode());}
    }
    private void updateSeen(JSONObject saved){try{isSeen=PlaybackState.status(saved).equals("finished");}catch(Exception ignored){}}
    private void mark(){if(token==null)return;boolean value=!isSeen;persist(false);disk.execute(()->{try{PlaybackProgress.mark(this,origin,token,id,value);JSONObject saved=PlaybackProgress.read(this,origin,token,id);runOnUiThread(()->updateSeen(saved));}catch(Exception error){runOnUiThread(()->notice(t("No se pudo guardar el progreso. Revisa la sesión y el espacio libre.")));}});}
    private void external(){if(uri==null||token==null)return;sample();persist(false);
        Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,getContentResolver().getType(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);view.setClipData(ClipData.newRawUri("TubeGo",uri));
        ExternalPlayers.choose(this,view,chosen->{try{synchronized(SessionStore.class){PlaybackProgress.session(this,origin,token);}startActivity(new Intent(view).setComponent(new ComponentName(chosen.activityInfo.packageName,chosen.activityInfo.name)));notice(t("El reproductor externo no guarda progreso periódico ni confirma lo visto."));}catch(Exception unavailable){notice(t("El reproductor ya no está disponible."));}});
    }
    private final java.util.List<android.app.Dialog> dialogs=new java.util.ArrayList<>();
    private PlayerMenu activeMenu;
    private android.app.AlertDialog.Builder dialog(String title){return new android.app.AlertDialog.Builder(this,R.style.PlayerDialog).setTitle(t(title));}
    private void showDialog(android.app.AlertDialog dialog){dialogs.add(dialog);dialog.setOnDismissListener(d->{dialogs.remove(dialog);if(!isFinishing()&&!isDestroyed())controls.show(true);});dialog.show();for(int which:new int[]{-1,-2,-3}){Button button=dialog.getButton(which);if(button!=null)button.setTextColor(PlayerControls.ORANGE);}}
    private void showOptions(){
        activeMenu=new PlayerMenu(this,()->{if(!isFinishing()&&!isDestroyed())controls.show(true);});
        activeMenu.item("lock","Bloquear",()->controls.lock(true));
        activeMenu.item("timer","Temporizador",this::sleepTimer);
        activeMenu.item("speed","Velocidad de reproducción",this::speed);
        activeMenu.item("forward","Ir al tiempo",this::jumpToTime);
        activeMenu.item("equalizer","Ecualizador",this::equalizer);
        activeMenu.item("aspect","Reproductor miniatura",this::pictureInPicture);
        activeMenu.item("repeat",repeat?"Desactivar repetición":"Repetir archivo actual",()->{repeat=!repeat;notice(t(repeat?"Repetir archivo actual":"No repetir"));});
        activeMenu.item("info","Información del vídeo",this::videoInformation);
        activeMenu.item("repeat",repeatStart<0?"Repetir A-B":repeatEnd<0?"Establecer final B":"Desactivar repetición A-B",this::repeatAB);
        activeMenu.item("more","Configuración de controles",this::controlSettings);
        activeMenu.item("check",isSeen?"Marcar como no visto":"Marcar como visto",this::mark);
        activeMenu.item("cast","Abrir externamente",this::external);
        activeMenu.show();
    }
    private void controlSettings(){
        String[] keys={"volume","brightness","double_tap","seek_buttons","hold_speed","pinch_scale"};
        String[] labels=Texts.labels(this,new String[]{"Gesto de volumen","Gesto de brillo","Doble toque para avanzar o retroceder","Botones de avance de 10 segundos","Mantener pulsado para reproducir a 2×","Zoom con dos dedos"});
        boolean[] checked=new boolean[keys.length];for(int i=0;i<keys.length;i++)checked[i]=preferences().getBoolean(keys[i],i!=3);
        showDialog(dialog("Configuración de controles").setMultiChoiceItems(labels,checked,(d,which,value)->{preferences().edit().putBoolean(keys[which],value).apply();controls.setSeekButtons(preferences().getBoolean("seek_buttons",false));}).setPositiveButton(t("Aceptar"),null).create());
    }
    private void toggleOrientation(){orientationLocked=!orientationLocked;setRequestedOrientation(orientationLocked?android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED:android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);controls.orientation.setImageDrawable(new PlayerIcon(orientationLocked?"lock":"rotate"));controls.orientation.setContentDescription(t(orientationLocked?"Desbloquear orientación":"Bloquear orientación"));}
    private void cycleAspect(){
        if(player==null)return;zoom=1;video.setScaleX(1);video.setScaleY(1);
        MediaPlayer.ScaleType[] types={MediaPlayer.ScaleType.SURFACE_BEST_FIT,MediaPlayer.ScaleType.SURFACE_FIT_SCREEN,MediaPlayer.ScaleType.SURFACE_FILL,MediaPlayer.ScaleType.SURFACE_16_9,MediaPlayer.ScaleType.SURFACE_4_3,MediaPlayer.ScaleType.SURFACE_ORIGINAL};
        String[] labels=Texts.labels(this,new String[]{"Mejor ajuste","Ajustar a pantalla","Rellenar","16:9","4:3","Tamaño original"});aspectIndex=(aspectIndex+1)%types.length;player.setVideoScale(types[aspectIndex]);controls.feedback("aspect",labels[aspectIndex],"");controls.finishFeedback();
    }
    private void showTracks(){
        if(player==null){notice(t("Preparando reproducción…"));return;}
        String[] items=Texts.labels(this,new String[]{"Pista de audio","Subtítulos","Sincronización de audio","Sincronización de subtítulos"});
        showDialog(dialog("Pistas de audio y subtítulos").setItems(items,(d,which)->{if(which<2)chooseTrack(which==1);else adjustDelay(which==3);}).create());
    }
    private void chooseTrack(boolean subtitles){
        if(player==null)return;MediaPlayer.TrackDescription[] tracks=subtitles?player.getSpuTracks():player.getAudioTracks();
        if(tracks==null||tracks.length==0){showDialog(dialog(subtitles?"Subtítulos":"Pista de audio").setMessage(t("No hay pistas disponibles en este archivo.")).setPositiveButton(t("Aceptar"),null).create());return;}
        String[] labels=new String[tracks.length];int current=subtitles?player.getSpuTrack():player.getAudioTrack(),selected=-1;
        for(int i=0;i<tracks.length;i++){labels[i]=tracks[i].id<0?t("Desactivar"):tracks[i].name;if(tracks[i].id==current)selected=i;}
        showDialog(dialog(subtitles?"Subtítulos":"Pista de audio").setSingleChoiceItems(labels,selected,(d,which)->{if(player!=null){if(subtitles)player.setSpuTrack(tracks[which].id);else player.setAudioTrack(tracks[which].id);}d.dismiss();}).setNegativeButton(t("Cancelar"),null).create());
    }
    private void adjustDelay(boolean subtitles){
        if(player==null)return;LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(24),dp(12),dp(24),dp(12));
        TextView value=new TextView(this);value.setTextColor(android.graphics.Color.WHITE);value.setTextSize(24);value.setGravity(Gravity.CENTER);body.addView(value);long[] delay={subtitles?player.getSpuDelay():player.getAudioDelay()};value.setText(delay[0]/1000+" ms");
        LinearLayout row=new LinearLayout(this);body.addView(row);
        for(int step:new int[]{-50,0,50}){Button b=new Button(this);b.setText(step==0?t("Restablecer"):step+" ms");row.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v->{if(player==null)return;delay[0]=step==0?0:delay[0]+step*1000L;if(subtitles)player.setSpuDelay(delay[0]);else player.setAudioDelay(delay[0]);value.setText(delay[0]/1000+" ms");});}
        showDialog(dialog(subtitles?"Sincronización de subtítulos":"Sincronización de audio").setView(body).setPositiveButton(t("Aceptar"),null).create());
    }
    private void speed(){
        if(player==null)return;LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(24),dp(12),dp(24),dp(12));
        TextView rate=new TextView(this);rate.setTextColor(android.graphics.Color.WHITE);rate.setTextSize(28);rate.setGravity(Gravity.CENTER);body.addView(rate);
        SeekBar slider=new SeekBar(this);slider.setTag("playback_speed");slider.setMax(75);slider.setProgressTintList(android.content.res.ColorStateList.valueOf(PlayerControls.ORANGE));slider.setThumbTintList(android.content.res.ColorStateList.valueOf(PlayerControls.ORANGE));slider.setProgress(Math.round((player.getRate()-.25f)/.05f));body.addView(slider);rate.setText(String.format(Locale.ROOT,"%.2f×",player.getRate()));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}public void onProgressChanged(SeekBar b,int value,boolean user){if(player!=null&&user){sample();timeline.discontinuity();float speed=.25f+value*.05f;player.setRate(speed);rate.setText(String.format(Locale.ROOT,"%.2f×",speed));}}});
        showDialog(dialog("Velocidad de reproducción").setView(body).setNeutralButton("1.00×",(d,w)->{if(player!=null){sample();timeline.discontinuity();player.setRate(1);}}).setPositiveButton(t("Aceptar"),null).create());
    }
    private void jumpToTime(){
        EditText input=new EditText(this);input.setSingleLine(true);input.setTextColor(android.graphics.Color.WHITE);input.setHintTextColor(0xffbbbbbb);input.setPadding(dp(24),dp(8),dp(24),dp(8));input.setBackgroundTintList(android.content.res.ColorStateList.valueOf(PlayerControls.ORANGE));input.setHint("hh:mm:ss");input.setText(timeline==null?"0:00":PlayerControls.clock(timeline.position));input.setInputType(android.text.InputType.TYPE_CLASS_DATETIME|android.text.InputType.TYPE_DATETIME_VARIATION_TIME);
        android.app.AlertDialog prompt=dialog("Ir al tiempo").setView(input).setPositiveButton(t("Aceptar"),null).setNegativeButton(t("Cancelar"),null).create();showDialog(prompt);
        prompt.getButton(-1).setOnClickListener(v->{try{long seconds=0;String[] parts=input.getText().toString().trim().split(":");if(parts.length>3)throw new IllegalArgumentException();for(String part:parts){long n=Long.parseLong(part);if(n<0||n>999999)throw new IllegalArgumentException();seconds=Math.addExact(Math.multiplyExact(seconds,60),n);}seekTo(Math.multiplyExact(seconds,1000));prompt.dismiss();}catch(Exception invalid){input.setError(t("Introduce un tiempo válido"));}});
    }
    private void sleepTimer(){String[] labels=Texts.labels(this,new String[]{"Desactivar","15 minutos","30 minutos","60 minutos"});showDialog(dialog("Temporizador").setItems(labels,(d,which)->{sleepAt=which==0?0:SystemClock.elapsedRealtime()+new long[]{0,15,30,60}[which]*60000;notice(t(which==0?"Temporizador desactivado":"Temporizador activado"));}).create());}
    private void equalizer(){
        if(player==null)return;int count=MediaPlayer.Equalizer.getPresetCount();String[] names=new String[count+1];names[0]=t("Desactivar");for(int i=0;i<count;i++)names[i+1]=MediaPlayer.Equalizer.getPresetName(i);
        showDialog(dialog("Ecualizador").setItems(names,(d,which)->{if(player!=null)player.setEqualizer(which==0?null:MediaPlayer.Equalizer.createFromPreset(which-1));}).create());
    }
    private void repeatAB(){if(timeline==null)return;if(repeatStart<0){repeatStart=timeline.position;notice(t("Inicio A establecido"));}else if(repeatEnd<0){if(timeline.position<=repeatStart+1000){notice(t("El final B debe estar después del inicio A"));return;}repeatEnd=timeline.position;notice(t("Repetición A-B activada"));}else{repeatStart=repeatEnd=-1;notice(t("Repetición A-B desactivada"));}}
    private void videoInformation(){if(player==null||timeline==null)return;String text=displayTitle+"\n"+PlayerControls.clock(timeline.duration)+"\n"+t("Pistas de vídeo")+": "+player.getVideoTracksCount()+"\n"+t("Pistas de audio")+": "+player.getAudioTracksCount();showDialog(dialog("Información del vídeo").setMessage(text).setPositiveButton(t("Aceptar"),null).create());}
    private void castScreen(){try{startActivity(new Intent(android.provider.Settings.ACTION_CAST_SETTINGS));}catch(ActivityNotFoundException unavailable){notice(t("La transmisión de pantalla no está disponible en este dispositivo."));}}
    private void pictureInPicture(){
        if(player==null)return;
        try{android.app.PictureInPictureParams params=new android.app.PictureInPictureParams.Builder().setAspectRatio(new android.util.Rational(16,9)).build();if(!enterPictureInPictureMode(params))notice(t("Activa el permiso de imagen en imagen para TubeGo."));}
        catch(IllegalStateException|IllegalArgumentException denied){notice(t("Activa el permiso de imagen en imagen para TubeGo."));}
    }

    @Override protected void onResume(){super.onResume();resumed=true;if(prepared)initialize();ui.removeCallbacks(tick);ui.post(tick);controls.show(true);render();}
    @Override protected void onPause(){if(gestureController!=null)gestureController.cancel();resumed=false;if(!isInPictureInPictureMode()){pausePlayback(true);ui.removeCallbacks(tick);if(mediaSession!=null)mediaSession.setActive(false);}super.onPause();}
    @Override protected void onStop(){pausePlayback(true);ui.removeCallbacks(tick);if(mediaSession!=null)mediaSession.setActive(false);super.onStop();}
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration){super.onConfigurationChanged(configuration);controls.requestApplyInsets();}
    @Override public void onPictureInPictureModeChanged(boolean pip,android.content.res.Configuration configuration){super.onPictureInPictureModeChanged(pip,configuration);controls.pip(pip);if(!pip&&!isFinishing()){ui.removeCallbacks(tick);ui.post(tick);}render();}
    @Override public void onBackPressed(){if(controls.locked())controls.showUnlock();else if(controls.showing())controls.show(false);else super.onBackPressed();}
    @Override protected void onDestroy(){ui.removeCallbacksAndMessages(null);if(gestureController!=null)gestureController.cancel();if(activeMenu!=null)activeMenu.dismiss();for(android.app.Dialog d:new java.util.ArrayList<>(dialogs))d.dismiss();if(controls!=null)controls.release();if(player!=null){player.setEventListener(null);player.stop();player.detachViews();player.release();}if(mediaSession!=null)mediaSession.release();if(engine!=null)engine.release();if(descriptor!=null)try{descriptor.close();}catch(Exception ignored){}disk.shutdown();super.onDestroy();}
}
