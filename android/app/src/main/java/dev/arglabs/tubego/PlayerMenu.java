package dev.arglabs.tubego;

import android.app.*;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.widget.*;

/** Right-edge video options panel with functional actions, not placeholder controls. */
final class PlayerMenu {
    private final Activity activity;
    private final Dialog dialog;
    private final LinearLayout list;
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    PlayerMenu(Activity activity,Runnable dismissed){
        this.activity=activity;dialog=new Dialog(activity,android.R.style.Theme_Material_NoActionBar);
        list=new LinearLayout(activity);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(0,dp(16),0,dp(16));list.setBackgroundColor(0xe61c1c1c);
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.addView(list);dialog.setContentView(scroll);dialog.setOnDismissListener(d->dismissed.run());
    }
    void item(String icon,String source,Runnable action){
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(12),dp(8),dp(12));row.setMinimumHeight(dp(52));
        ImageView image=new ImageView(activity);image.setImageDrawable(new PlayerIcon(icon));row.addView(image,new LinearLayout.LayoutParams(dp(28),dp(28)));
        TextView label=new TextView(activity);label.setText(Texts.text(activity,source));label.setTextSize(20);label.setTextColor(Color.WHITE);LinearLayout.LayoutParams text=new LinearLayout.LayoutParams(0,-2,1);text.leftMargin=dp(16);row.addView(label,text);
        row.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ffffff),null,new ColorDrawable(Color.WHITE)));
        row.setOnClickListener(v->{dialog.dismiss();action.run();});list.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    void dismiss(){dialog.dismiss();}
    void show(){dialog.show();Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));window.setNavigationBarColor(Color.BLACK);window.setStatusBarColor(Color.TRANSPARENT);window.setNavigationBarContrastEnforced(false);window.getDecorView().setSystemUiVisibility(0);window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setGravity(Gravity.END);window.setLayout(dp(224),-1);}}
}
