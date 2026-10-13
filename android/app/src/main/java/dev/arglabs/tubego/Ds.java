package dev.arglabs.tubego;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Sistema visual de TubeGo: colores, tipografía y componentes básicos. Solo presentación. */
final class Ds {
    private Ds() {}

    // Colores (contrastes verificados en el tablero "Sistema visual").
    static final int PAPER = 0xFFF6F5F1;
    static final int INK = 0xFF1F2230;
    static final int MUTED = 0xFF575C6B;
    static final int LINE = 0xFFE2E0D8;
    static final int CARD = 0xFFFFFFFF;
    static final int BRAND = 0xFF674AE5;
    static final int BRAND_DARK = 0xFF4B32BE;
    static final int SOFT = 0xFFECE8FD;
    static final int SOFT_SELECTED = 0xFFF4F1FE;
    static final int LIME = 0xFFDDF48D;
    static final int LIME_INK = 0xFF2C4A0B;
    static final int WARN_BG = 0xFFFFEFD0;
    static final int WARN = 0xFF744400;
    static final int WARN_DEEP = 0xFF4A2C00;
    static final int ERR_BG = 0xFFFDE8E6;
    static final int ERR = 0xFFA4231B;
    static final int NEUTRAL_BG = 0xFFF0EFE9;
    static final int OUTLINE = 0xFFCFCCC1;
    static final int SCRIM = 0x8C1F2230;
    static final int TRACK = 0xFFE4E0F7;

    enum Tone { NEUTRAL, WAIT, PROGRESS, OK, ATTENTION }

    enum Kind { PRIMARY, SECONDARY, TEXT, DANGER, DANGER_TEXT }

    static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    static Typeface weight(int w) {
        return Typeface.create(Typeface.DEFAULT, w, false);
    }

    static GradientDrawable rounded(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) d.setStroke(Math.max(1, dp(c, 1)), stroke);
        return d;
    }

    static Drawable ripple(Context c, Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(0x22000000), content, null);
    }

    static TextView text(Context c, CharSequence value, float sp, int color, int weight) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(weight(weight));
        t.setLineSpacing(0, 1.12f);
        return UiKit.custom(t);
    }

    static LinearLayout column(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    static LinearLayout row(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    /** Añade un hijo a un LinearLayout con márgenes en dp. */
    static void add(LinearLayout parent, View child, int width, int height, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        Context c = parent.getContext();
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        parent.addView(child, p);
    }

    static void add(LinearLayout parent, View child, float top) {
        add(parent, child, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, top, 0, 0);
    }

    static void addWeighted(LinearLayout parent, View child, float weight, float l, float r) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        Context c = parent.getContext();
        p.setMargins(dp(c, l), 0, dp(c, r), 0);
        parent.addView(child, p);
    }

    static ImageView icon(Context c, int res, int tint) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(tint));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    static ImageButton iconButton(Context c, int res, String description) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(res);
        b.setImageTintList(ColorStateList.valueOf(INK));
        b.setContentDescription(description);
        b.setBackground(ripple(c, rounded(c, 0x00000000, 0, 24)));
        b.setScaleType(ImageView.ScaleType.CENTER);
        return UiKit.custom(b);
    }

    static Button button(Context c, String label, Kind kind) {
        Button b = new Button(c);
        b.setText(label);
        styleButton(b, kind);
        return UiKit.custom(b);
    }

    static void styleButton(Button b, Kind kind) {
        Context c = b.getContext();
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTypeface(weight(700));
        b.setStateListAnimator(null);
        b.setMinHeight(dp(c, 48));
        b.setMinimumHeight(dp(c, 48));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        int fill, foreground;
        switch (kind) {
            case PRIMARY: fill = BRAND; foreground = CARD; break;
            case SECONDARY: fill = SOFT; foreground = BRAND_DARK; break;
            case DANGER: fill = ERR; foreground = CARD; break;
            case DANGER_TEXT: fill = 0; foreground = ERR; break;
            default: fill = 0; foreground = BRAND_DARK;
        }
        ColorStateList colors = new ColorStateList(
                new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {MUTED, foreground});
        GradientDrawable shape = rounded(c, fill, 0, 16);
        shape.setColor(new ColorStateList(
                new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {NEUTRAL_BG, fill}));
        b.setTextColor(colors);
        b.setBackground(ripple(c, shape));
    }

    static void window(android.app.Activity a) {
        a.getWindow().setStatusBarColor(PAPER);
        a.getWindow().setNavigationBarColor(PAPER);
        a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        a.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    static void insets(View view) {
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                int keyboard = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard));
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
    }

    static LinearLayout option(Context c, int icon, String title, String detail, Runnable action) {
        LinearLayout row = row(c);
        row.setBackground(ripple(c, rounded(c, CARD, LINE, 20)));
        row.setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14));
        row.setMinimumHeight(dp(c, 76));
        FrameLayout stamp = new FrameLayout(c);
        stamp.setBackground(rounded(c, SOFT, 0, 14));
        stamp.addView(icon(c, icon, BRAND_DARK), new FrameLayout.LayoutParams(dp(c, 24), dp(c, 24), Gravity.CENTER));
        row.addView(stamp, new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)));
        LinearLayout text = column(c);
        text.addView(text(c, title, 16, INK, 700));
        add(text, text(c, detail, 13, MUTED, 500), 4);
        addWeighted(row, text, 1, 12, 10);
        row.addView(icon(c, R.drawable.ic_ds_chevron, MUTED), new LinearLayout.LayoutParams(dp(c, 20), dp(c, 20)));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(title + ", " + detail);
        row.setOnClickListener(v -> action.run());
        return row;
    }

    static int bigButtonHeight(Context c) { return dp(c, 56); }

    static TextView badge(Context c, String label, Tone tone) {
        int bg, fg, iconRes;
        switch (tone) {
            case WAIT: bg = WARN_BG; fg = WARN; iconRes = R.drawable.ic_ds_clock; break;
            case PROGRESS: bg = SOFT; fg = BRAND_DARK; iconRes = R.drawable.ic_ds_download; break;
            case OK: bg = LIME; fg = LIME_INK; iconRes = R.drawable.ic_ds_check; break;
            case ATTENTION: bg = ERR_BG; fg = ERR; iconRes = R.drawable.ic_ds_alert; break;
            default: bg = NEUTRAL_BG; fg = MUTED; iconRes = 0; break;
        }
        TextView t = text(c, label, 13, fg, 700);
        t.setBackground(rounded(c, bg, 0, 20));
        t.setPadding(dp(c, 10), dp(c, 5), dp(c, 12), dp(c, 5));
        t.setGravity(Gravity.CENTER_VERTICAL);
        if (iconRes != 0) {
            Drawable d = c.getDrawable(iconRes).mutate();
            d.setTint(fg);
            int s = dp(c, 16);
            d.setBounds(0, 0, s, s);
            t.setCompoundDrawablesRelative(d, null, null, null);
            t.setCompoundDrawablePadding(dp(c, 6));
        }
        return UiKit.custom(t);
    }

    static LinearLayout card(Context c) {
        LinearLayout v = column(c);
        v.setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16));
        v.setBackground(rounded(c, CARD, LINE, 24));
        return v;
    }

    /** Recuadro con icono por formato (hasta que haya miniaturas). */
    static FrameLayout thumb(Context c, boolean audio, int bg, int fg, float sizeDp) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(rounded(c, bg, 0, sizeDp * 0.29f));
        ImageView i = icon(c, audio ? R.drawable.ic_ds_audio : R.drawable.ic_ds_video, fg);
        int s = dp(c, sizeDp * 0.43f);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(s, s, Gravity.CENTER);
        f.addView(i, p);
        return f;
    }

    /** Barra de progreso redondeada. */
    static View progress(Context c, int percent, int fill, int track, String description) {
        Bar bar = new Bar(c, percent, fill, track);
        bar.setContentDescription(description);
        return bar;
    }

    /** Barra de espacio con segmentos y una marca (el mínimo libre). */
    static View segments(Context c, float[] fractions, int[] colors, float marker, String description) {
        Segments s = new Segments(c, fractions, colors, marker);
        s.setContentDescription(description);
        return s;
    }

    private static final class Bar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int percent, fill, track;

        Bar(Context c, int percent, int fill, int track) {
            super(c);
            this.percent = Math.max(0, Math.min(100, percent));
            this.fill = fill;
            this.track = track;
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), dp(getContext(), 10));
        }

        @Override protected void onDraw(Canvas canvas) {
            float r = getHeight() / 2f;
            paint.setColor(track);
            canvas.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()), r, r, paint);
            if (percent > 0) {
                paint.setColor(fill);
                float right = Math.max(getHeight(), getWidth() * percent / 100f);
                canvas.drawRoundRect(new RectF(0, 0, right, getHeight()), r, r, paint);
            }
        }
    }

    private static final class Segments extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] fractions;
        private final int[] colors;
        private final float marker;

        Segments(Context c, float[] fractions, int[] colors, float marker) {
            super(c);
            this.fractions = fractions;
            this.colors = colors;
            this.marker = marker;
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), dp(getContext(), 24));
        }

        @Override protected void onDraw(Canvas canvas) {
            float top = dp(getContext(), 4), bottom = getHeight() - top, w = getWidth(), r = (bottom - top) / 2f;
            paint.setColor(0xFFFFFFFF);
            canvas.drawRoundRect(new RectF(0, top, w, bottom), r, r, paint);
            canvas.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(new RectF(0, top, w, bottom), r, r, android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            float x = 0;
            for (int i = 0; i < fractions.length; i++) {
                float next = x + w * Math.max(0, fractions[i]);
                paint.setColor(colors[i]);
                canvas.drawRect(x, top, next, bottom, paint);
                x = next;
            }
            canvas.restore();
            if (marker > 0 && marker < 1) {
                paint.setColor(WARN_DEEP);
                float mx = w * marker, half = dp(getContext(), 1.5f);
                canvas.drawRoundRect(new RectF(mx - half, 0, mx + half, getHeight()), half, half, paint);
            }
        }
    }
}
