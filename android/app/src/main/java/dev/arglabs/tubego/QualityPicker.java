package dev.arglabs.tubego;

import android.app.Activity;
import android.app.AlertDialog;
import java.util.function.Consumer;

/** Reusable offline dialog for URL entry / share flow; creates no backend job. */
public final class QualityPicker {
    private QualityPicker() {}
    public static void show(Activity activity, String currentSelection, Consumer<String> selected) {
        final int[] choice = {MediaSelection.index(currentSelection)};
        new AlertDialog.Builder(activity).setTitle("Calidad o formato")
            .setSingleChoiceItems(MediaSelection.LABELS, choice[0], (dialog, index) -> choice[0] = index)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Usar selección", (dialog, which) -> selected.accept(MediaSelection.VALUES[choice[0]]))
            .show();
    }
}
