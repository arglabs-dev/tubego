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
 public static final int PAPER=Color.rgb(246,245,241), INK=Color.rgb(34,37,44), MUTED=Color.rgb(103,108,120), PURPLE=Color.rgb(103,74,229), LIME=Color.rgb(221,244,141), LINE=Color.rgb(227,227,234), WHITE=Color.WHITE;
 private static final Set<View> styled=Collections.newSetFromMap(new WeakHashMap<>());
 private UiKit(){}
 public static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
 public static <T extends View>T custom(T view){styled.add(view);return view;}
 public static GradientDrawable rounded(int color,int border,float radius,Context c){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));if(border!=0)d.setStroke(dp(c,1),border);return d;}
 public static android.content.res.ColorStateList states(int enabled,int disabled){return new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{disabled,enabled});}
 public static LinearLayout column(Context c){LinearLayout v=new LinearLayout(c);v.setOrientation(LinearLayout.VERTICAL);return v;}
 public static TextView text(Context c,String value,float size,int color){TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("kern");t.setLineSpacing(dp(c,3),1);custom(t);return t;}
 public static TextView heading(Context c,String value){TextView t=text(c,value,23,INK);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
 public static void margin(View v,int top,int bottom){ViewGroup.LayoutParams p=v.getLayoutParams();if(p instanceof LinearLayout.LayoutParams l){l.topMargin=dp(v.getContext(),top);l.bottomMargin=dp(v.getContext(),bottom);}}
 public static LinearLayout card(Context c){LinearLayout v=column(c);v.setPadding(dp(c,18),dp(c,18),dp(c,18),dp(c,18));v.setBackground(rounded(WHITE,LINE,24,c));custom(v);return v;}
 public static void section(LinearLayout parent,String label){TextView t=text(parent.getContext(),Texts.text(parent.getContext(),label),12,MUTED);t.setTypeface(Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));t.setLetterSpacing(.10f);parent.addView(t);margin(t,22,10);}
 public static void button(Button b,boolean primary){Context c=b.getContext();b.setAllCaps(false);b.setTextSize(15);b.setTypeface(Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));b.setTextColor(states(primary?INK:INK,MUTED));b.setGravity(Gravity.CENTER);b.setMinHeight(dp(c,52));b.setMinimumHeight(dp(c,52));b.setPadding(dp(c,16),dp(c,12),dp(c,16),dp(c,12));b.setStateListAnimator(null);GradientDrawable shape=rounded(primary?LIME:WHITE,primary?0:LINE,16,c);shape.setColor(states(primary?LIME:WHITE,Color.rgb(235,235,233)));b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18674AE5),shape,null));custom(b);}
 public static void tile(Button b,String icon){button(b,false);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setCompoundDrawablePadding(dp(b.getContext(),14));Glyph g=new Glyph(icon,PURPLE,b.getContext());g.setBounds(0,0,dp(b.getContext(),25),dp(b.getContext(),25));Glyph chevron=new Glyph("next",MUTED,b.getContext());chevron.setBounds(0,0,dp(b.getContext(),18),dp(b.getContext(),18));b.setCompoundDrawables(g,null,chevron,null);}
 public static TextView badge(Context c,String label,boolean ready){TextView t=text(c,label,11,ready?Color.rgb(55,88,25):MUTED);t.setTypeface(Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));t.setPadding(dp(c,10),dp(c,5),dp(c,10),dp(c,5));t.setBackground(rounded(ready?0xFFEAF4D6:0xFFF0EFF6,0,20,c));return t;}
 public static LinearLayout hero(Context c){LinearLayout box=card(c);box.setBackground(rounded(LIME,0,28,c));box.setPadding(dp(c,24),dp(c,22),dp(c,24),dp(c,24));TextView eyebrow=text(c,Texts.text(c,"TU TIEMPO. TU CONTENIDO."),11,INK);eyebrow.setLetterSpacing(.13f);box.addView(eyebrow);TextView title=heading(c,Texts.text(c,"Aprende a\ntu ritmo."));title.setTextSize(38);title.setLineSpacing(0,1);box.addView(title);margin(title,16,10);TextView sub=text(c,Texts.text(c,"Guarda una vez.\nEscucha donde quieras."),16,INK);box.addView(sub);return box;}
 public static LinearLayout nav(Activity a,int selected,Runnable home,Runnable library,Runnable add,Runnable settings){LinearLayout bar=new LinearLayout(a);bar.setGravity(Gravity.CENTER);bar.setPadding(dp(a,10),dp(a,8),dp(a,10),dp(a,8));bar.setBackgroundColor(WHITE);custom(bar);String[] names={"Inicio","Biblioteca","Agregar","Ajustes"},icons={"home","library","add","settings"};Runnable[] actions={home,library,add,settings};for(int i=0;i<4;i++){final int at=i;LinearLayout item=column(a);item.setGravity(Gravity.CENTER);item.setPadding(0,dp(a,6),0,dp(a,6));item.setBackground(rounded(i==selected?0xFFF0EDFF:WHITE,0,16,a));item.setMinimumHeight(dp(a,52));item.setClickable(true);item.setFocusable(true);item.setContentDescription(Texts.text(a,names[i]));item.setSelected(i==selected);custom(item);ImageView icon=new ImageView(a);icon.setImageDrawable(new Glyph(icons[i],i==selected?PURPLE:MUTED,a));icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);item.addView(icon,new LinearLayout.LayoutParams(dp(a,24),dp(a,24)));TextView label=text(a,Texts.text(a,names[i]),11,i==selected?PURPLE:MUTED);label.setTypeface(Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));item.addView(label);item.setOnClickListener(v->actions[at].run());bar.addView(item,new LinearLayout.LayoutParams(0,-2,1));}return bar;}
 public static View shell(LocalizedActivity a,View body){LinearLayout shell=column(a);shell.setBackgroundColor(PAPER);custom(shell);boolean main=a instanceof MainActivity;
  if(!main){LinearLayout toolbar=new LinearLayout(a);toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setPadding(dp(a,14),dp(a,8),dp(a,20),dp(a,6));custom(toolbar);ImageButton back=new ImageButton(a);back.setImageDrawable(new Glyph("back",INK,a));back.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18674AE5),rounded(PAPER,0,24,a),null));back.setContentDescription(Texts.text(a,"Volver"));back.setOnClickListener(v->a.finish());toolbar.addView(back,new LinearLayout.LayoutParams(dp(a,48),dp(a,48)));TextView brand=heading(a,"tubego");brand.setTextSize(20);toolbar.addView(brand);shell.addView(toolbar);
   if(body instanceof LinearLayout l)l.setPadding(dp(a,20),dp(a,12),dp(a,20),dp(a,20));else if(body instanceof ScrollView s&&s.getChildCount()>0){View v=s.getChildAt(0);v.setPadding(dp(a,20),dp(a,12),dp(a,20),dp(a,24));}
  }
  shell.addView(body,new LinearLayout.LayoutParams(-1,0,1));
  if(a instanceof LocalLibraryActivity||a instanceof LinkEntryActivity){String origin=a.getIntent().getStringExtra("server_url");Runnable home=()->a.startActivity(new Intent(a,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));Runnable library=()->{if(!(a instanceof LocalLibraryActivity))a.startActivity(new Intent(a,LocalLibraryActivity.class).putExtra("server_url",origin));};Runnable add=()->{if(!(a instanceof LinkEntryActivity))a.startActivity(new Intent(a,LinkEntryActivity.class).putExtra("server_url",origin));};Runnable settings=()->a.startActivity(new Intent(a,MainActivity.class).putExtra("open_settings",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));shell.addView(nav(a,a instanceof LocalLibraryActivity?1:2,home,library,add,settings));}
  shell.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());int keyboard=insets.getInsets(WindowInsets.Type.ime()).bottom;v.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,keyboard));}else{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());}return insets;});
  if(Build.VERSION.SDK_INT>=30&&a.getWindow().getDecorView().getWindowInsetsController()!=null)a.getWindow().getDecorView().getWindowInsetsController().setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
  a.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  style(body);shell.getViewTreeObserver().addOnGlobalLayoutListener(()->style(body));shell.requestApplyInsets();return shell;
 }
 private static void style(View v){if(styled.add(v)){Context c=v.getContext();
  if(v instanceof CompoundButton b){b.setTextColor(INK);b.setButtonTintList(ColorStateList.valueOf(PURPLE));b.setPadding(0,dp(c,8),0,dp(c,8));}
  else if(v instanceof Button b){button(b,false);if(b.getParent() instanceof LinearLayout p&&p.getOrientation()==LinearLayout.VERTICAL){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(c,4);lp.bottomMargin=dp(c,6);b.setLayoutParams(lp);}}
  else if(v instanceof EditText e){e.setTextColor(INK);e.setHintTextColor(MUTED);e.setTextSize(16);e.setPadding(dp(c,16),dp(c,14),dp(c,16),dp(c,14));e.setBackground(rounded(WHITE,LINE,16,c));e.setMinimumHeight(dp(c,54));e.setBackgroundTintList(null);margin(e,5,8);}
  else if(v instanceof TextView t){t.setTextColor(t.getTextSize()/c.getResources().getDisplayMetrics().scaledDensity>=22?INK:MUTED);t.setLineSpacing(dp(c,3),1);if(t.getTextSize()/c.getResources().getDisplayMetrics().scaledDensity>=22){t.setTypeface(Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));margin(t,6,14);}else margin(t,3,7);}
  else if(v instanceof Spinner s){s.setBackground(rounded(WHITE,LINE,16,c));s.setPadding(dp(c,12),dp(c,8),dp(c,12),dp(c,8));s.setMinimumHeight(dp(c,52));margin(s,4,8);}
  if(v instanceof ScrollView s){s.setFillViewport(true);s.setClipToPadding(false);s.setVerticalScrollBarEnabled(false);}
  if(v instanceof ProgressBar p)p.setProgressTintList(ColorStateList.valueOf(PURPLE));
 }
 if(v instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++)style(g.getChildAt(i));}
 public static final class Glyph extends Drawable {
  private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private final String name;private final Context context;
  public Glyph(String name,int color,Context context){this.name=name;this.context=context;p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.8f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);}
  @Override public void draw(Canvas c){c.save();c.translate(getBounds().left,getBounds().top);c.scale(getBounds().width()/24f,getBounds().height()/24f);Path q=new Path();switch(name){
   case "home":q.moveTo(3,11);q.lineTo(12,3);q.lineTo(21,11);q.moveTo(5,10);q.lineTo(5,21);q.lineTo(10,21);q.lineTo(10,15);q.lineTo(14,15);q.lineTo(14,21);q.lineTo(19,21);q.lineTo(19,10);c.drawPath(q,p);break;
   case "library":c.drawRoundRect(4,4,20,20,3,3,p);q.moveTo(10,9);q.lineTo(16,12);q.lineTo(10,15);q.close();c.drawPath(q,p);break;
   case "add":c.drawRoundRect(3,3,21,21,6,6,p);c.drawLine(12,7,12,17,p);c.drawLine(7,12,17,12,p);break;
   case "settings":c.drawLine(4,7,20,7,p);c.drawLine(4,17,20,17,p);c.drawCircle(9,7,3,p);c.drawCircle(16,17,3,p);break;
   case "back":q.moveTo(15,5);q.lineTo(8,12);q.lineTo(15,19);c.drawPath(q,p);break;
   case "next":q.moveTo(9,6);q.lineTo(15,12);q.lineTo(9,18);c.drawPath(q,p);break;
   case "account":c.drawCircle(12,8,4,p);c.drawArc(4,13,20,26,180,180,false,p);break;
   case "cloud":q.moveTo(6,18);q.cubicTo(0,18,2,10,7,11);q.cubicTo(8,1,20,5,19,11);q.cubicTo(25,14,21,18,18,18);q.lineTo(6,18);c.drawPath(q,p);break;
   case "audio":c.drawArc(4,4,20,20,180,180,false,p);c.drawRoundRect(3,11,7,20,2,2,p);c.drawRoundRect(17,11,21,20,2,2,p);break;
   case "history":c.drawCircle(12,12,9,p);c.drawLine(12,7,12,12,p);c.drawLine(12,12,16,14,p);break;
   case "play":q.moveTo(8,5);q.lineTo(20,12);q.lineTo(8,19);q.close();c.drawPath(q,p);break;
   default:c.drawRoundRect(4,4,20,20,4,4,p);c.drawLine(8,9,16,9,p);c.drawLine(8,14,16,14,p);
  }c.restore();}
  @Override public void setAlpha(int a){p.setAlpha(a);}@Override public void setColorFilter(ColorFilter f){p.setColorFilter(f);}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}@Override public int getIntrinsicWidth(){return dp(context,24);}@Override public int getIntrinsicHeight(){return dp(context,24);}
 }
 public static final class Cover extends View {
  private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private final int seed;private final String format;
  public Cover(Context c,String id,String format){super(c);seed=id.hashCode();this.format=format;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);custom(this);}
  @Override protected void onDraw(Canvas canvas){float w=getWidth(),h=getHeight();int[] palette={0xFFCFC6FC,0xFFDDF48D,0xFFBDE6DF,0xFFF4D2AF};int base=palette[Math.floorMod(seed,palette.length)];p.setColor(base);canvas.drawRoundRect(0,0,w,h,dp(getContext(),16),dp(getContext(),16),p);p.setColor(0x34FFFFFF);canvas.drawCircle(w*.92f,h*.12f,w*.55f,p);p.setColor(0x26000000);canvas.drawCircle(w*.04f,h*.96f,w*.45f,p);Glyph icon=new Glyph("audio".equals(format)?"audio":"play",INK,getContext());int size=dp(getContext(),28);icon.setBounds((int)(w-size)/2,(int)(h-size)/2,(int)(w+size)/2,(int)(h+size)/2);icon.draw(canvas);}
 }
}
