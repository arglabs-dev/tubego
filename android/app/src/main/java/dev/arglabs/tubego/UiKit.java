package dev.arglabs.tubego;

import android.app.Activity;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Native, accessible visual system. Styling never changes data, permissions or listeners. */
public final class UiKit {
 public static final int PAPER=Ds.PAPER, INK=Ds.INK, MUTED=Ds.MUTED, PURPLE=Ds.BRAND, LIME=Ds.LIME, LINE=Ds.LINE, WHITE=Ds.CARD;
 private static final Set<View> styled=Collections.newSetFromMap(new WeakHashMap<>());
 private UiKit(){}
 public static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
 public static <T extends View>T custom(T view){styled.add(view);return view;}
 public static GradientDrawable rounded(int color,int border,float radius,Context c){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));if(border!=0)d.setStroke(dp(c,1),border);return d;}
 public static android.content.res.ColorStateList states(int enabled,int disabled){return new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{disabled,enabled});}
 public static LinearLayout column(Context c){LinearLayout v=new LinearLayout(c);v.setOrientation(LinearLayout.VERTICAL);return v;}
 public static TextView text(Context c,String value,float size,int color){TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setTypeface(Ds.weight(500));t.setFontFeatureSettings("kern");t.setLineSpacing(0,1.12f);custom(t);return t;}
 public static TextView heading(Context c,String value){TextView t=text(c,value,23,INK);t.setTypeface(Ds.weight(800));return t;}
 public static void margin(View v,int top,int bottom){ViewGroup.LayoutParams p=v.getLayoutParams();if(p instanceof LinearLayout.LayoutParams l){l.topMargin=dp(v.getContext(),top);l.bottomMargin=dp(v.getContext(),bottom);}}
 public static LinearLayout card(Context c){LinearLayout v=column(c);v.setPadding(dp(c,18),dp(c,18),dp(c,18),dp(c,18));v.setBackground(rounded(WHITE,LINE,24,c));custom(v);return v;}
 public static void section(LinearLayout parent,String label){TextView t=text(parent.getContext(),Texts.text(parent.getContext(),label),12,MUTED);t.setTypeface(Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));t.setLetterSpacing(.10f);parent.addView(t);margin(t,22,10);}
 public static void button(Button b,boolean primary){Ds.styleButton(b,primary?Ds.Kind.PRIMARY:Ds.Kind.SECONDARY);custom(b);}
 public static void danger(Button b){Ds.styleButton(b,Ds.Kind.DANGER_TEXT);custom(b);}
 public static void tile(Button b,String icon){button(b,false);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setCompoundDrawablePadding(dp(b.getContext(),14));Glyph g=new Glyph(icon,PURPLE,b.getContext());g.setBounds(0,0,dp(b.getContext(),25),dp(b.getContext(),25));Glyph chevron=new Glyph("next",MUTED,b.getContext());chevron.setBounds(0,0,dp(b.getContext(),18),dp(b.getContext(),18));b.setCompoundDrawables(g,null,chevron,null);}
 public static TextView badge(Context c,String label,boolean ready){return custom(Ds.badge(c,label,ready?Ds.Tone.OK:Ds.Tone.NEUTRAL));}
 public static void sectionCard(LinearLayout parent, String title, String description){
  LinearLayout card=Ds.card(parent.getContext());custom(card);
  card.addView(Ds.text(parent.getContext(),Texts.text(parent.getContext(),title),18,Ds.INK,800));
  Ds.add(card,Ds.text(parent.getContext(),Texts.text(parent.getContext(),description),14,Ds.MUTED,500),6);
  Ds.add(parent,card,16);
 }
 public static View shell(LocalizedActivity a,View body){
  ScreenInfo.Info info=ScreenInfo.forScreen(a.getClass());
  LinearLayout shell=Ds.column(a);shell.setTag("ds_screen");shell.setBackgroundColor(Ds.PAPER);custom(shell);
  LinearLayout toolbar=Ds.row(a);toolbar.setPadding(dp(a,8),dp(a,8),dp(a,20),dp(a,8));custom(toolbar);
  ImageButton back=Ds.iconButton(a,R.drawable.ic_ds_back,Texts.text(a,"Volver"));back.setOnClickListener(v->a.finish());
  toolbar.addView(back,new LinearLayout.LayoutParams(dp(a,48),dp(a,48)));
  Ds.addWeighted(toolbar,Ds.text(a,Texts.text(a,info.title()),23,Ds.INK,800),1,8,0);shell.addView(toolbar);
  LinearLayout content=Ds.column(a);content.setPadding(dp(a,20),dp(a,4),dp(a,20),dp(a,28));custom(content);
  LinearLayout intro=Ds.card(a);intro.setTag("screen_intro");
  LinearLayout summary=Ds.row(a);
  android.widget.FrameLayout stamp=Ds.thumb(a,false,Ds.SOFT,Ds.BRAND_DARK,48);
  ((ImageView)stamp.getChildAt(0)).setImageResource(info.icon());
  summary.addView(stamp,new LinearLayout.LayoutParams(dp(a,48),dp(a,48)));
  LinearLayout copy=Ds.column(a);
  copy.addView(Ds.badge(a,Texts.text(a,info.scope()),info.admin()?Ds.Tone.PROGRESS:Ds.Tone.NEUTRAL));
  Ds.add(copy,Ds.text(a,Texts.text(a,info.description()),14,Ds.MUTED,500),6);
  Ds.addWeighted(summary,copy,1,12,0);intro.addView(summary);Ds.add(content,intro,0);
  // Keep the original form/list intact; dynamic rows can be replaced without losing the heading.
  if(body instanceof ScrollView scroll&&scroll.getChildCount()>0){View child=scroll.getChildAt(0);scroll.removeView(child);body=child;}
  if(body instanceof LinearLayout column && column.getChildCount()>0 && "legacy_heading".equals(column.getChildAt(0).getTag()))column.removeViewAt(0);
  body.setPadding(0,dp(a,16),0,0);content.addView(body,new LinearLayout.LayoutParams(-1,-2));
  ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);scroll.addView(content);
  shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
  Ds.window(a);Ds.insets(shell);style(body);
  final View form=body;shell.getViewTreeObserver().addOnGlobalLayoutListener(()->style(form));shell.requestApplyInsets();return shell;
 }
 private static void style(View v){if(styled.add(v)){Context c=v.getContext();
  if(v instanceof CompoundButton b){b.setTextColor(INK);b.setTextSize(16);b.setTypeface(Ds.weight(500));b.setMinimumHeight(dp(c,48));b.setButtonTintList(ColorStateList.valueOf(PURPLE));b.setPadding(0,dp(c,8),0,dp(c,8));}
  else if(v instanceof Button b){button(b,false);if(b.getParent() instanceof LinearLayout p&&p.getOrientation()==LinearLayout.VERTICAL){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(c,4);lp.bottomMargin=dp(c,6);b.setLayoutParams(lp);}}
  else if(v instanceof EditText e){e.setTextColor(INK);e.setHintTextColor(MUTED);e.setTextSize(16);e.setPadding(dp(c,16),dp(c,14),dp(c,16),dp(c,14));e.setBackground(rounded(WHITE,Ds.OUTLINE,16,c));e.setMinimumHeight(dp(c,54));e.setBackgroundTintList(null);margin(e,5,8);}
  else if(v instanceof TextView t){t.setTextColor(t.getTextSize()/c.getResources().getDisplayMetrics().scaledDensity>=22?INK:MUTED);t.setLineSpacing(0,1.12f);if(t.getTextSize()/c.getResources().getDisplayMetrics().scaledDensity>=22){t.setTypeface(Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));margin(t,6,14);}else margin(t,3,7);}
  else if(v instanceof Spinner s){s.setBackground(rounded(WHITE,LINE,16,c));s.setPadding(dp(c,12),dp(c,8),dp(c,12),dp(c,8));s.setMinimumHeight(dp(c,52));margin(s,4,8);}
  if(v instanceof ScrollView s){s.setFillViewport(true);s.setClipToPadding(false);s.setVerticalScrollBarEnabled(false);}
  if(v instanceof ProgressBar p){p.setProgressTintList(ColorStateList.valueOf(PURPLE));p.setProgressBackgroundTintList(ColorStateList.valueOf(Ds.TRACK));}
 }
 if(v instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++)style(g.getChildAt(i));}
 public static final class Glyph extends Drawable {
  private final Drawable icon;private final Context context;
  public Glyph(String name,int color,Context context){this.context=context;int res=switch(name){case "back"->R.drawable.ic_ds_back;case "next"->R.drawable.ic_ds_chevron;case "add"->R.drawable.ic_ds_plus;case "settings"->R.drawable.ic_ds_sliders;case "audio"->R.drawable.ic_ds_audio;case "play"->R.drawable.ic_ds_play;case "history"->R.drawable.ic_ds_clock;case "account"->R.drawable.ic_ds_info;case "cloud"->R.drawable.ic_ds_download;default->R.drawable.ic_ds_video;};icon=context.getDrawable(res).mutate();icon.setTint(color);}
  @Override public void draw(Canvas canvas){icon.setBounds(getBounds());icon.draw(canvas);}
  @Override public void setAlpha(int alpha){icon.setAlpha(alpha);}
  @Override public void setColorFilter(ColorFilter filter){icon.setColorFilter(filter);}
  @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
  @Override public int getIntrinsicWidth(){return dp(context,24);}
  @Override public int getIntrinsicHeight(){return dp(context,24);}
 }
 public static final class Cover extends View {
  private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private final int seed;private final String format;
  public Cover(Context c,String id,String format){super(c);seed=id.hashCode();this.format=format;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);custom(this);}
  @Override protected void onDraw(Canvas canvas){float w=getWidth(),h=getHeight();p.setColor(Ds.SOFT);canvas.drawRoundRect(0,0,w,h,dp(getContext(),16),dp(getContext(),16),p);Drawable icon=getContext().getDrawable("audio".equals(format)?R.drawable.ic_ds_audio:R.drawable.ic_ds_video).mutate();icon.setTint(Ds.BRAND_DARK);int size=dp(getContext(),28);icon.setBounds((int)(w-size)/2,(int)(h-size)/2,(int)(w+size)/2,(int)(h+size)/2);icon.draw(canvas);}
 }
}
