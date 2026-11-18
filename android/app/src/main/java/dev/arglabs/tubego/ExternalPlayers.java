package dev.arglabs.tubego;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** Device preference only. Choosing a player never records playback progress. */
final class ExternalPlayers {
    private ExternalPlayers() {}

    private static String key(Intent view) {
        return view.getType() != null && view.getType().startsWith("audio/") ? "audio" : "video";
    }

    private static List<ResolveInfo> available(Activity activity, Intent view) {
        List<ResolveInfo> players = new ArrayList<>(activity.getPackageManager().queryIntentActivities(view, 0));
        players.sort(Comparator.comparing(p -> !"org.videolan.vlc".equals(p.activityInfo.packageName)));
        return players;
    }

    private static String component(ResolveInfo player) {
        return new ComponentName(player.activityInfo.packageName, player.activityInfo.name).flattenToString();
    }

    static void choose(Activity activity, Intent view, Consumer<ResolveInfo> launch) {
        List<ResolveInfo> players = available(activity, view);
        String saved = activity.getSharedPreferences("external_players", Context.MODE_PRIVATE).getString(key(view), "");
        for (ResolveInfo player : players) {
            if (component(player).equals(saved)) { launch.accept(player); return; }
        }
        // A removed/disabled player or one incompatible with this MIME falls back to the picker.
        if (players.isEmpty()) {
            new AlertDialog.Builder(activity).setMessage(Texts.text(activity,
                    "No hay un reproductor compatible. Instala VLC u otro y vuelve a intentarlo."))
                    .setPositiveButton(Texts.text(activity, "Aceptar"), null).show();
            return;
        }
        if (players.size() == 1) { launch.accept(players.get(0)); return; }
        new AlertDialog.Builder(activity).setTitle(Texts.text(activity, "Reproducir con…"))
                .setItems(names(activity, players, false), (dialog, which) -> launch.accept(players.get(which))).show();
    }

    static void configure(Activity activity) {
        new AlertDialog.Builder(activity).setTitle(Texts.text(activity, "Reproductor externo predeterminado"))
                .setItems(Texts.labels(activity, new String[]{"Video", "Audio"}), (dialog, which) -> {
                    Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(
                            Uri.parse("content://dev.arglabs.tubego.private-media/media/player-selection"),
                            which == 0 ? "video/mp4" : "audio/mpeg");
                    List<ResolveInfo> players = available(activity, view);
                    String saved = activity.getSharedPreferences("external_players", Context.MODE_PRIVATE).getString(key(view), "");
                    int selected = 0;
                    for (int n = 0; n < players.size(); n++) if (component(players.get(n)).equals(saved)) selected = n + 1;
                    new AlertDialog.Builder(activity).setTitle(Texts.text(activity, which == 0 ? "Video" : "Audio"))
                            .setSingleChoiceItems(names(activity, players, true), selected, (picker, index) -> {
                                activity.getSharedPreferences("external_players", Context.MODE_PRIVATE).edit()
                                        .putString(key(view), index == 0 ? "" : component(players.get(index - 1))).apply();
                                picker.dismiss();
                            }).setNegativeButton(Texts.text(activity, "Cancelar"), null).show();
                }).show();
    }

    private static String[] names(Activity activity, List<ResolveInfo> players, boolean includeAsk) {
        int offset = includeAsk ? 1 : 0;
        String[] names = new String[players.size() + offset];
        if (includeAsk) names[0] = Texts.text(activity, "Preguntar cada vez");
        for (int n = 0; n < players.size(); n++) names[n + offset] = players.get(n).loadLabel(activity.getPackageManager()).toString();
        return names;
    }
}
