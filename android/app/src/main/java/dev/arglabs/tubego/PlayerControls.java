package dev.arglabs.tubego;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.util.Locale;

/** Full-screen overlay: transparent edges, VLC-like placement, orange timeline and auto-hide. */
final class PlayerControls extends FrameLayout {
    interface Actions {
        void toggle();void seek(long position);void tracks();void orientation();void aspect();void options();void back();void cast();void visible(boolean visible);
    }
    static final int ORANGE=0xffff610a;
    final View gestures;
    final ImageButton toggle,orientation;
    final SeekBar seek,unlock;
    final TextView message;private final ProgressBar loading;
    private final TextView title,elapsed,remaining,hudValue,hudDetail;
    private final ImageView hudIcon;
    private final LinearLayout top,bottom,hud;
    private final ImageButton seekBack,seekForward;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Actions actions;
    private boolean visible=true,playing,dragging,locked,pip,remainingMode=true;
    private long position,duration;
    private int insetLeft,insetTop,insetRight,insetBottom;
    private final Runnable hide=()->show(false);
    private final Runnable hideHud;
    private final Runnable hideNotice;
    private final Runnable hideUnlock;
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private String t(String source){return Texts.text(getContext(),source);}
    private TextView text(int size){TextView v=new TextView(getContext());v.setTextColor(Color.WHITE);v.setTextSize(size);v.setShadowLayer(dp(2),0,dp(1),Color.BLACK);return v;}
    PlayerControls(Context context,Actions actions){
        super(context);setTag("playback_controls");this.actions=actions;setBackgroundColor(Color.TRANSPARENT);
        gestures=new View(context);gestures.setTag("playback_gestures");gestures.setContentDescription(t("Avance horizontal; brillo a la izquierda y volumen a la derecha"));addView(gestures,new LayoutParams(-1,-1));
        top=new LinearLayout(context);top.setClickable(true);top.setGravity(Gravity.CENTER_VERTICAL);top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xb3000000,0x00000000}));
        ImageButton back=icon("back","Cerrar",actions::back);top.addView(back,new LinearLayout.LayoutParams(dp(48),dp(56)));
        title=text(22);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);title.setGravity(Gravity.CENTER);title.setText(t("Reproducción"));top.addView(title,new LinearLayout.LayoutParams(0,dp(56),1));
        top.addView(icon("cast","Transmitir pantalla",actions::cast),new LinearLayout.LayoutParams(dp(48),dp(56)));addView(top,new LayoutParams(-1,dp(72),Gravity.TOP));
        bottom=new LinearLayout(context);bottom.setClickable(true);bottom.setOrientation(LinearLayout.VERTICAL);bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00000000,0xc9000000}));
        LinearLayout times=new LinearLayout(context);times.setGravity(Gravity.CENTER_VERTICAL);elapsed=text(14);remaining=text(14);remaining.setGravity(Gravity.END);times.addView(elapsed,new LinearLayout.LayoutParams(0,dp(24),1));times.addView(remaining,new LinearLayout.LayoutParams(0,dp(24),1));bottom.addView(times);
        remaining.setOnClickListener(v->{remainingMode=!remainingMode;render(position,duration);interact();});
        seek=new SeekBar(context);seek.setTag("playback_seek");seek.setContentDescription(t("Posición de reproducción"));seek.setMax(1000);seek.setProgressTintList(ColorStateList.valueOf(ORANGE));seek.setThumbTintList(ColorStateList.valueOf(ORANGE));seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x55444444));seek.setPadding(0,0,0,0);bottom.addView(seek,new LinearLayout.LayoutParams(-1,dp(24)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar bar){dragging=true;handler.removeCallbacks(hide);}public void onProgressChanged(SeekBar bar,int progress,boolean user){if(user){long target=duration*progress/1000;elapsed.setText(clock(target));feedback("forward",clock(target),clock(duration));}}public void onStopTrackingTouch(SeekBar bar){dragging=false;actions.seek(duration*bar.getProgress()/1000);finishFeedback();interact();}});
        LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);bottom.addView(row,new LinearLayout.LayoutParams(-1,dp(64)));
        row.addView(icon("tracks","Pistas de audio y subtítulos",actions::tracks),new LinearLayout.LayoutParams(dp(48),dp(48)));
        orientation=icon("rotate","Bloquear orientación",actions::orientation);orientation.setTag("playback_orientation");row.addView(orientation,new LinearLayout.LayoutParams(dp(48),dp(48)));
        row.addView(new View(context),new LinearLayout.LayoutParams(0,1,1));
        seekBack=icon("seek_back","Retroceder 10 segundos",()->actions.seek(position-10000));row.addView(seekBack,new LinearLayout.LayoutParams(dp(48),dp(48)));
        toggle=icon("play","Reproducir",actions::toggle);toggle.setTag("playback_toggle");toggle.setPadding(dp(8),dp(8),dp(8),dp(8));row.addView(toggle,new LinearLayout.LayoutParams(dp(64),dp(64)));
        seekForward=icon("seek_forward","Avanzar 10 segundos",()->actions.seek(position+10000));row.addView(seekForward,new LinearLayout.LayoutParams(dp(48),dp(48)));
        row.addView(new View(context),new LinearLayout.LayoutParams(0,1,1));
        row.addView(icon("aspect","Relación de aspecto",actions::aspect),new LinearLayout.LayoutParams(dp(48),dp(48)));
        ImageButton more=icon("more","Más opciones de reproducción",actions::options);more.setTag("playback_more");row.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));
        addView(bottom,new LayoutParams(-1,-2,Gravity.BOTTOM));setSeekButtons(false);
        hud=new LinearLayout(context);hud.setOrientation(LinearLayout.VERTICAL);hud.setGravity(Gravity.CENTER);hud.setPadding(dp(20),dp(12),dp(20),dp(12));hud.setBackground(round(0xc9222222,8));
        hudIcon=new ImageView(context);hud.addView(hudIcon,new LinearLayout.LayoutParams(dp(32),dp(32)));hudValue=text(24);hudValue.setTag("playback_hud_value");hudValue.setGravity(Gravity.CENTER);hud.addView(hudValue);hudDetail=text(14);hudDetail.setGravity(Gravity.CENTER);hud.addView(hudDetail);addView(hud,new LayoutParams(dp(180),-2,Gravity.CENTER));hud.setVisibility(GONE);
        message=text(14);message.setGravity(Gravity.CENTER);message.setPadding(dp(16),dp(8),dp(16),dp(8));message.setBackground(round(0xe6333333,8));LayoutParams notice=new LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);notice.topMargin=dp(80);addView(message,notice);message.setVisibility(GONE);
        unlock=new SeekBar(context);unlock.setTag("playback_unlock");unlock.setMax(100);unlock.setContentDescription(t("Desliza para desbloquear"));unlock.setProgressTintList(ColorStateList.valueOf(ORANGE));unlock.setThumbTintList(ColorStateList.valueOf(Color.WHITE));unlock.setBackground(round(0xc9333333,28));unlock.setPadding(dp(16),dp(8),dp(16),dp(8));LayoutParams slider=new LayoutParams(dp(240),dp(56),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);slider.bottomMargin=dp(28);addView(unlock,slider);unlock.setVisibility(GONE);
        unlock.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){handler.removeCallbacks(hideUnlock);}public void onProgressChanged(SeekBar b,int p,boolean user){if(user&&p>=90){lock(false);}}public void onStopTrackingTouch(SeekBar b){b.setProgress(0);if(locked)showUnlock();}});
        hideHud=()->hud.setVisibility(GONE);hideNotice=()->message.setVisibility(GONE);hideUnlock=()->unlock.setVisibility(GONE);
        loading=new ProgressBar(context);loading.setIndeterminateTintList(ColorStateList.valueOf(ORANGE));addView(loading,new LayoutParams(dp(48),dp(48),Gravity.CENTER));
        render(0,0);
    }
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private ImageButton icon(String kind,String label,Runnable action){ImageButton b=new ImageButton(getContext());b.setImageDrawable(new PlayerIcon(kind));b.setPadding(dp(12),dp(12),dp(12),dp(12));b.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33ffffff),null,round(Color.WHITE,32)));b.setContentDescription(t(label));b.setOnClickListener(v->{action.run();interact();});return b;}
    void insets(int left,int topInset,int right,int bottomInset){insetLeft=left;insetTop=topInset;insetRight=right;insetBottom=bottomInset;top.setPadding(left+dp(8),topInset,right+dp(8),dp(8));top.getLayoutParams().height=topInset+dp(72);top.requestLayout();bottom.setPadding(left+dp(16),dp(8),right+dp(16),bottomInset+dp(8));LayoutParams p=(LayoutParams)message.getLayoutParams();p.topMargin=topInset+dp(76);message.setLayoutParams(p);LayoutParams u=(LayoutParams)unlock.getLayoutParams();u.bottomMargin=bottomInset+dp(28);unlock.setLayoutParams(u);}
    void loading(boolean value){loading.setVisibility(value&&!pip?VISIBLE:GONE);}
    void title(String value){title.setText(value);}
    void setPlaying(boolean value){boolean changed=playing!=value;playing=value;if(changed){toggle.setImageDrawable(new PlayerIcon(value?"pause":"play"));toggle.setContentDescription(t(value?"Pausar":"Reproducir"));}if(changed&&!locked&&!pip){if(!value)show(true);else interact();}}
    void render(long value,long length){position=value;duration=length;if(!dragging){elapsed.setText(clock(value));remaining.setText(remainingMode?"− "+clock(Math.max(0,length-value)):clock(length));seek.setProgress(length>0?(int)Math.min(1000,value*1000/length):0);}}
    void setSeekButtons(boolean enabled){seekBack.setVisibility(enabled?VISIBLE:GONE);seekForward.setVisibility(enabled?VISIBLE:GONE);}
    boolean showing(){return visible;}
    void toggleVisible(){if(locked){showUnlock();return;}show(!visible);}
    void show(boolean value){handler.removeCallbacks(hide);visible=value&&!locked&&!pip;top.setVisibility(visible?VISIBLE:GONE);bottom.setVisibility(visible?VISIBLE:GONE);actions.visible(visible);if(visible&&playing&&!dragging)handler.postDelayed(hide,3000);}
    void interact(){if(visible&&playing&&!dragging){handler.removeCallbacks(hide);handler.postDelayed(hide,3000);}}
    void feedback(String icon,String value,String detail){handler.removeCallbacks(hideHud);hudIcon.setImageDrawable(new PlayerIcon(icon));hudValue.setText(value);hudDetail.setText(detail);hudDetail.setVisibility(detail.isEmpty()?GONE:VISIBLE);hud.setVisibility(pip?GONE:VISIBLE);}
    void finishFeedback(){handler.removeCallbacks(hideHud);handler.postDelayed(hideHud,900);}
    void notice(String value){message.setText(value);message.setVisibility(pip?GONE:VISIBLE);handler.removeCallbacks(hideNotice);handler.postDelayed(hideNotice,4000);}
    void lock(boolean value){locked=value;if(value){show(false);notice(t("Controles bloqueados"));}else{unlock.setVisibility(GONE);show(true);}}
    boolean locked(){return locked;}
    void showUnlock(){unlock.setProgress(0);unlock.setVisibility(VISIBLE);notice(t("Desliza para desbloquear"));handler.removeCallbacks(hideUnlock);handler.postDelayed(hideUnlock,4000);}
    void pip(boolean value){pip=value;loading.setVisibility(GONE);gestures.setVisibility(value?GONE:VISIBLE);if(value){show(false);hud.setVisibility(GONE);message.setVisibility(GONE);unlock.setVisibility(GONE);}else show(true);}
    void release(){handler.removeCallbacksAndMessages(null);}
    static String clock(long ms){long s=Math.max(0,ms)/1000;return s>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",s/3600,s/60%60,s%60):String.format(Locale.ROOT,"%d:%02d",s/60,s%60);}
}
