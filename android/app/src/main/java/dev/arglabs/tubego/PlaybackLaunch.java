package dev.arglabs.tubego;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;

/** Integrated by default; external preference and explicit external opening remain available. */
final class PlaybackLaunch {
    private PlaybackLaunch() {}
    static void open(Activity activity,String origin,String id,Runnable external) {
        if(activity.getSharedPreferences("external_players",Context.MODE_PRIVATE).getBoolean("use_external",false))external.run();
        else activity.startActivity(new Intent(activity,PlayerActivity.class).putExtra("server_url",origin).putExtra("resource_id",id));
    }
    static void configure(Activity activity) {
        String[] labels=Texts.labels(activity,new String[]{"Integrado: guardar y continuar","Externo: seguimiento limitado"});
        int selected=activity.getSharedPreferences("external_players",Context.MODE_PRIVATE).getBoolean("use_external",false)?1:0;
        new AlertDialog.Builder(activity).setTitle(Texts.text(activity,"Modo de reproducción"))
                .setSingleChoiceItems(labels,selected,(dialog,which)->{
                    activity.getSharedPreferences("external_players",Context.MODE_PRIVATE).edit().putBoolean("use_external",which==1).apply();dialog.dismiss();
                }).setNegativeButton(Texts.text(activity,"Cancelar"),null).show();
    }
}
