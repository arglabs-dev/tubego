package dev.arglabs.tubego;

import android.content.Context;
import android.text.*;
import android.view.*;
import android.widget.*;

/** Presentation-only dashboard; original authenticated navigation handlers remain authoritative. */
final class DashboardView extends LinearLayout {
 private final MainActivity activity;private final ScrollView home,settings;private final LinearLayout homeBody,settingsBody;private final FrameLayout pages;private View navigation;private Runnable goLibrary,goAdd;
 DashboardView(MainActivity a,EditText url,Button connect,TextView status,Button account,Button registration,Button addLink,Button library,Button recovery,Button commands,Button mediaPrefs,Button dataPolicy,Button diagnostics,Button cleanup,Button deletion,Button language,Button help,Button admin){
  super(a);activity=a;setOrientation(VERTICAL);UiKit.custom(this);setBackgroundColor(UiKit.PAPER);
  LinearLayout brand=new LinearLayout(a);brand.setGravity(Gravity.CENTER_VERTICAL);brand.setPadding(UiKit.dp(a,22),UiKit.dp(a,12),UiKit.dp(a,22),UiKit.dp(a,8));UiKit.custom(brand);
  ImageView mark=new ImageView(a);mark.setImageDrawable(new UiKit.Glyph("play",UiKit.LIME,a));mark.setPadding(UiKit.dp(a,6),UiKit.dp(a,6),UiKit.dp(a,6),UiKit.dp(a,6));mark.setBackground(UiKit.rounded(UiKit.PURPLE,0,10,a));mark.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);LinearLayout.LayoutParams markParams=new LinearLayout.LayoutParams(UiKit.dp(a,32),UiKit.dp(a,32));markParams.rightMargin=UiKit.dp(a,10);brand.addView(mark,markParams);TextView name=UiKit.heading(a,"tubego");name.setTextSize(27);brand.addView(name,new LinearLayout.LayoutParams(0,-2,1));brand.addView(UiKit.badge(a,Texts.text(a,"PARA LLEVAR"),true));addView(brand);
  homeBody=UiKit.column(a);settingsBody=UiKit.column(a);homeBody.setPadding(UiKit.dp(a,20),UiKit.dp(a,12),UiKit.dp(a,20),UiKit.dp(a,24));settingsBody.setPadding(UiKit.dp(a,20),UiKit.dp(a,12),UiKit.dp(a,20),UiKit.dp(a,24));UiKit.custom(homeBody);UiKit.custom(settingsBody);
  home=new ScrollView(a);home.addView(homeBody);settings=new ScrollView(a);settings.addView(settingsBody);pages=new FrameLayout(a);pages.addView(home,new FrameLayout.LayoutParams(-1,-1));pages.addView(settings,new FrameLayout.LayoutParams(-1,-1));settings.setVisibility(GONE);addView(pages,new LinearLayout.LayoutParams(-1,0,1));
  LinearLayout hero=UiKit.hero(a);homeBody.addView(hero);addLink.setText(Texts.text(a,"Agregar enlace"));UiKit.button(addLink,true);addLink.setTextColor(UiKit.WHITE);addLink.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x30FFFFFF),UiKit.rounded(UiKit.INK,0,16,a),null));hero.addView(addLink,new LinearLayout.LayoutParams(-1,-2));UiKit.margin(addLink,22,0);
  UiKit.section(homeBody,"TU ESPACIO");library.setText(Texts.text(a,"Mi biblioteca"));tile(homeBody,library,"library");recovery.setText(Texts.text(a,"Historial"));tile(homeBody,recovery,"history");commands.setText(Texts.text(a,"Pendientes y errores"));tile(homeBody,commands,"cloud");
  LinearLayout note=UiKit.card(a);TextView headline=UiKit.text(a,Texts.text(a,"Una descarga. Muchas reproducciones."),16,UiKit.INK);headline.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));note.addView(headline);TextView explanation=UiKit.text(a,Texts.text(a,"Descargas por Wi-Fi. Reproducción sin conexión."),14,UiKit.MUTED);note.addView(explanation);UiKit.margin(explanation,6,0);homeBody.addView(note);UiKit.margin(note,16,0);
  TextView homeStatus=UiKit.text(a,status.getText().toString(),12,UiKit.MUTED);homeBody.addView(homeStatus);UiKit.margin(homeStatus,14,0);status.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){homeStatus.setText(s);}public void afterTextChanged(Editable e){}});
  Button setup=new Button(a);setup.setText(Texts.text(a,"Configurar mi servidor"));UiKit.tile(setup,"settings");homeBody.addView(setup,new LinearLayout.LayoutParams(-1,-2));UiKit.margin(setup,10,0);setup.setOnClickListener(v->showSettings(true));
  settingsBody.addView(UiKit.heading(a,Texts.text(a,"Ajustes")));TextView intro=UiKit.text(a,Texts.text(a,"Tu cuenta, tus dispositivos y tu forma de descargar."),14,UiKit.MUTED);settingsBody.addView(intro);
  UiKit.section(settingsBody,"CONEXIÓN");LinearLayout server=UiKit.card(a);server.addView(UiKit.text(a,Texts.text(a,"Servidor de Tubego"),16,UiKit.INK));server.addView(url,new LinearLayout.LayoutParams(-1,-2));connect.setText(Texts.text(a,"Conectar servidor"));UiKit.button(connect,true);server.addView(connect,new LinearLayout.LayoutParams(-1,-2));UiKit.margin(connect,8,8);server.addView(status);settingsBody.addView(server);
  UiKit.section(settingsBody,"CUENTA");tile(settingsBody,account,"account");tile(settingsBody,registration,"account");
  UiKit.section(settingsBody,"REPRODUCCIÓN Y DESCARGAS");tile(settingsBody,mediaPrefs,"play");tile(settingsBody,dataPolicy,"cloud");tile(settingsBody,deletion,"library");tile(settingsBody,cleanup,"cloud");
  UiKit.section(settingsBody,"PREFERENCIAS Y AYUDA");tile(settingsBody,language,"settings");tile(settingsBody,help,"library");tile(settingsBody,diagnostics,"settings");tile(settingsBody,admin,"settings");
  goLibrary=()->navigate(url,library,status);goAdd=()->navigate(url,addLink,status);navigation=UiKit.nav(a,0,()->showSettings(false),goLibrary,goAdd,()->showSettings(true));addView(navigation);
  // Only setup determines onboarding; no fabricated connection, library counts or media.
  if(url.getText().toString().trim().isEmpty())showSettings(true);
 }
 private void navigate(EditText url,Button target,TextView status){try{new ApiClient(url.getText().toString());target.performClick();}catch(Exception e){showSettings(true);status.setText(Texts.error(activity,e));}}
 private void tile(LinearLayout parent,Button button,String icon){UiKit.tile(button,icon);parent.addView(button,new LinearLayout.LayoutParams(-1,-2));UiKit.margin(button,0,8);}
 boolean settingsVisible(){return settings.getVisibility()==VISIBLE;}
 void showSettings(boolean value){home.setVisibility(value?GONE:VISIBLE);settings.setVisibility(value?VISIBLE:GONE);if(navigation!=null){removeView(navigation);navigation=UiKit.nav(activity,value?3:0,()->showSettings(false),goLibrary,goAdd,()->showSettings(true));addView(navigation);}}
}
