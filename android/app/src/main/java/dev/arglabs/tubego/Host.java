package dev.arglabs.tubego;

import java.util.concurrent.ExecutorService;

/** Lo que las hojas y acciones necesitan de la pantalla que las abre. */
interface Host {
    DsActivity act();
    String origin();
    /** Muestra un mensaje breve al pie de la pantalla. */
    void message(String text);
    /** Vuelve a cargar la biblioteca. */
    void refresh();
    ExecutorService io();
    /** Red actual (para saber si hay conexión). */
    DownloadPolicy.NetworkState network();
}
