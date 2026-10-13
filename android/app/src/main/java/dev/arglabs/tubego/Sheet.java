package dev.arglabs.tubego;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Hoja inferior reutilizable (agregar enlace, detalle, borrar, espacio). */
final class Sheet {
    private final Activity activity;
    private final Dialog dialog;
    private final LinearLayout body;

    Sheet(Activity activity) {
        this.activity = activity;
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = Ds.column(activity);
        float r = Ds.dp(activity, 28);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(bg);
        int side = Ds.dp(activity, 20);
        sheet.setPadding(side, Ds.dp(activity, 10), side, Ds.dp(activity, 24));
        View handle = new View(activity);
        handle.setBackground(Ds.rounded(activity, Ds.OUTLINE, 0, 2));
        Ds.add(sheet, handle, Ds.dp(activity, 40), Ds.dp(activity, 4), 0, 0, 0, 14);
        ((LinearLayout.LayoutParams) handle.getLayoutParams()).gravity = Gravity.CENTER_HORIZONTAL;
        body = Ds.column(activity);
        sheet.addView(body, new LinearLayout.LayoutParams(-1, -2));
        sheet.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(side, Ds.dp(activity, 10), side, Ds.dp(activity, 20) + bars.bottom);
            }
            return insets;
        });
        ScrollView scroll = new ScrollView(activity);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(sheet, new ViewGroup.LayoutParams(-1, -2));
        dialog.setContentView(scroll);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.55f);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    LinearLayout body() { return body; }

    /** Fila de título con botón de cerrar. */
    void title(String text, String closeLabel) {
        LinearLayout row = Ds.row(activity);
        TextView t = Ds.text(activity, text, 22, Ds.INK, 800);
        Ds.addWeighted(row, t, 1, 0, 8);
        android.widget.ImageButton close = Ds.iconButton(activity, R.drawable.ic_ds_close, closeLabel);
        close.setOnClickListener(v -> dismiss());
        Ds.add(row, close, Ds.dp(activity, 48), Ds.dp(activity, 48), 0, 0, -8, 0);
        Ds.add(body, row, 0);
    }

    void show() { if (!activity.isFinishing() && !activity.isDestroyed()) dialog.show(); }

    void dismiss() { if (dialog.isShowing()) dialog.dismiss(); }

    boolean isShowing() { return dialog.isShowing(); }
}
