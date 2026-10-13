package dev.arglabs.tubego;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.json.JSONObject;

/** Review every native screen with synthetic data; no actions reach a real server. */
@SuppressWarnings("deprecation")
public final class VisualRedesignInstrumentedTest extends InstrumentationTestCase {
    private static final String ORIGIN = "https://10.0.2.2:9445";
    private Context context;
    private SSLSocketFactory original;
    private String previousOrigin;
    private JSONObject fixture;

    @Override protected void setUp() throws Exception {
        super.setUp();
        context = getInstrumentation().getTargetContext();
        previousOrigin = context.getSharedPreferences("server_connection", Context.MODE_PRIVATE).getString("server_url", null);
        context.getSharedPreferences("server_connection", Context.MODE_PRIVATE).edit().putString("server_url", ORIGIN).commit();
        original = HttpsURLConnection.getDefaultSSLSocketFactory();
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null);
        Context test = getInstrumentation().getContext();
        int id = test.getResources().getIdentifier("visual_cert", "raw", test.getPackageName());
        try (InputStream cert = test.getResources().openRawResource(id)) { trust.setCertificateEntry("visual", CertificateFactory.getInstance("X.509").generateCertificate(cert)); }
        TrustManagerFactory manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); manager.init(trust);
        SSLContext tls = SSLContext.getInstance("TLS"); tls.init(null, manager.getTrustManagers(), null);
        HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
        account("admin", "es");
        assertEquals("ui-fixture", new ApiClient(ORIGIN).health().getString("version"));
    }

    private void account(String role, String language) throws Exception {
        fixture = new JSONObject().put("token", "ui-" + role + "-" + language).put("status", "approved").put("role", role)
                .put("email", "persona@example.invalid").put("user_id", UUID.randomUUID().toString()).put("device_id", UUID.randomUUID().toString());
        new SessionStore(context, ORIGIN).save(fixture);
        LanguagePreferences.saveLocal(context, ORIGIN, language);
        TransferJobs.stop(context, ORIGIN);
    }

    @Override protected void tearDown() throws Exception {
        TransferJobs.stop(context, ORIGIN);
        new SessionStore(context, ORIGIN).clear();
        if (fixture != null) LocalLibraryStorage.wipe(context, ORIGIN, fixture.getString("user_id"), fixture.getString("device_id"));
        var preferences = context.getSharedPreferences("server_connection", Context.MODE_PRIVATE).edit();
        if (previousOrigin == null) preferences.remove("server_url"); else preferences.putString("server_url", previousOrigin);
        preferences.commit();
        HttpsURLConnection.setDefaultSSLSocketFactory(original);
        super.tearDown();
    }

    private Activity open(Class<?> target) {
        Activity activity = getInstrumentation().startActivitySync(new Intent(context, target).putExtra("server_url", ORIGIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        getInstrumentation().waitForIdleSync();
        return activity;
    }
    private void close(Activity activity) { getInstrumentation().runOnMainSync(activity::finish); getInstrumentation().waitForIdleSync(); }

    public void testLibraryRestoresPersistentTransferScheduling() throws Exception {
        android.app.job.JobScheduler scheduler = context.getSystemService(android.app.job.JobScheduler.class);
        assertNull(scheduler.getPendingJob(TransferJobs.jobId(ORIGIN, true)));
        Activity activity = open(LibraryActivity.class);
        try {
            android.app.job.JobInfo job = scheduler.getPendingJob(TransferJobs.jobId(ORIGIN, true));
            assertNotNull("Opening the library must restore background synchronization", job);
            assertTrue(job.isPersisted());
            assertEquals(ORIGIN, job.getExtras().getString("origin"));
        } finally { close(activity); }
    }

    public void testEveryManifestScreenUsesSharedVisualSystem() throws Exception {
        PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_ACTIVITIES);
        int checked = 0;
        for (var entry : installed.activities) {
            Class<?> target = Class.forName(entry.name);
            ScreenInfo.Info info = ScreenInfo.forScreen(target);
            if (target == OnboardingActivity.class || target == ShareReceiverActivity.class) continue;
            Activity activity = open(target);
            try {
                View root = activity.getWindow().getDecorView().findViewWithTag("ds_screen");
                assertNotNull(entry.name + " must use the shared shell", root);
                assertEquals(entry.name + " paper background", Ds.PAPER, ((ColorDrawable) root.getBackground()).getColor());
                assertNotNull(entry.name + " title", find(root, info.title()));
                assertButtonLabels(root);
                if (target == LibraryActivity.class) assertNotNull("Add link button must keep its label", find(root, "  Agregar enlace"));
                if (activity instanceof LocalizedActivity) {
                    assertNotNull(entry.name + " scope", find(root, info.scope()));
                    assertNotNull(entry.name + " explanation", find(root, info.description()));
                }
                if (target == AdminRetentionActivity.class) waitFor(activity, "Plazos para todos los usuarios");
                else if (target == AdminPriorityActivity.class) waitFor(activity, "Guardar prioridad");
                else if (target == AdminUsersActivity.class) waitFor(activity, "Administración de usuarios");
                else if (target == AdminMailActivity.class) waitFor(activity, "La contraseña nunca se muestra en esta pantalla.");
                else if (target == AdminCenterActivity.class) waitFor(activity, "Aprobar cuentas");
                else if (target == LibraryActivity.class || target == LocalLibraryActivity.class) waitFor(activity, "Ideas para aprender algo nuevo");
                Thread.sleep(500); getInstrumentation().waitForIdleSync();
                capture(target.getSimpleName()); checked++;
            } finally { close(activity); }
        }
        assertEquals("All screens except onboarding and share receiver", 28, checked);
    }

    public void testSettingsDistinguishUserAndAdministratorAndRefreshRole() throws Exception {
        account("user", "es"); Activity settings = open(SettingsActivity.class);
        try {
            View root = settings.getWindow().getDecorView();
            assertNotNull(find(root, "Descargas y reproducción"));
            assertNotNull(find(root, "Este teléfono"));
            assertNotNull(find(root, "Mis archivos en el servidor"));
            assertNull(find(root, "Administración"));
            assertNull(find(root, "Administración del servidor"));
            capture("Settings-user");
            Activity connection = open(MainActivity.class);
            account("admin", "es"); close(connection); getInstrumentation().waitForIdleSync();
            assertNotNull(find(root, "Administración"));
            assertNotNull(find(root, "Solo administradores"));
            assertNotNull(find(root, "Administración del servidor"));
            Thread.sleep(250); getInstrumentation().waitForIdleSync();
            getInstrumentation().runOnMainSync(() -> { scroll(root).setSmoothScrollingEnabled(false); scroll(root).fullScroll(View.FOCUS_DOWN); });
            getInstrumentation().waitForIdleSync(); capture("Settings-admin-bottom");
        } finally { close(settings); }
    }

    public void testPersonalSettingsOpenTheDescribedScreens() throws Exception {
        account("user", "es"); Activity settings = open(SettingsActivity.class);
        try {
            for (Class<?> target : new Class<?>[] {MediaPreferencesActivity.class, ResubmitActivity.class, NetworkPolicyActivity.class,
                    DeviceStorageActivity.class, MainActivity.class, LoginActivity.class, DevicesActivity.class, LanguageActivity.class,
                    AlertsActivity.class, ServerCleanupActivity.class, DownloadFailuresActivity.class, CommandsActivity.class,
                    DiagnosticsActivity.class, HelpActivity.class}) {
                ScreenInfo.Info info = ScreenInfo.forScreen(target);
                View label = find(settings.getWindow().getDecorView(), info.title()); assertNotNull(info.title(), label);
                View row = label; while (!row.isClickable()) row = (View) row.getParent();
                Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(target.getName(), null, false);
                try {
                    View option = row; getInstrumentation().runOnMainSync(option::performClick);
                    Activity child = getInstrumentation().waitForMonitorWithTimeout(monitor, 4000);
                    assertNotNull("Navigation to " + target.getName(), child); close(child);
                } finally { getInstrumentation().removeMonitor(monitor); }
            }
        } finally { close(settings); }
    }

    public void testAdminMenuHasClearGroupsAndServerStorageGraph() throws Exception {
        Activity admin = open(AdminCenterActivity.class);
        try {
            waitFor(admin, "Aprobar cuentas");
            View root = admin.getWindow().getDecorView();
            for (String label : new String[] {"Cuentas y acceso", "Descargas del servidor", "Servicio y mantenimiento", "Usuarios y permisos", "Limpieza global del servidor", "Mantenimiento del servidor"}) assertNotNull(label, find(root, label));
            Thread.sleep(500); capture("AdminCenter-loaded");
            assertNotNull(description(root, "Uso del servidor y límite del 90 %"));
            Thread.sleep(250); getInstrumentation().waitForIdleSync();
            getInstrumentation().runOnMainSync(() -> { scroll(root).setSmoothScrollingEnabled(false); scroll(root).fullScroll(View.FOCUS_DOWN); });
            getInstrumentation().waitForIdleSync(); capture("AdminCenter-bottom");
        } finally { close(admin); }
    }

    public void testEnglishSettingsKeepPurposeAndAdministratorScope() throws Exception {
        account("admin", "en"); Activity activity = open(SettingsActivity.class);
        try {
            View root = activity.getWindow().getDecorView();
            assertNotNull(find(root, "Settings")); assertNotNull(find(root, "Administrators only"));
            assertNotNull(find(root, "Quality and playback")); assertNotNull(find(root, "My account and security"));
            capture("Settings-English");
        } finally { close(activity); }
    }

    public void testOnboardingAndInvalidShareUseTheSamePalette() throws Exception {
        new SessionStore(context, ORIGIN).clear();
        Activity onboarding = open(OnboardingActivity.class);
        try {
            View root = onboarding.getWindow().getDecorView();
            assertNotNull(root.findViewWithTag("ds_screen"));
            assertNotNull(find(root, Texts.text(onboarding, "Iniciar sesión")));
            assertNotNull(find(root, Texts.text(onboarding, "Crear cuenta")));
            assertButtonLabels(root); capture("Onboarding");
        } finally { close(onboarding); }
        Activity share = open(ShareReceiverActivity.class);
        try { assertNotNull(share.getWindow().getDecorView().findViewWithTag("ds_screen")); capture("ShareReceiver-invalid"); } finally { close(share); }
    }

    public void testSessionConfirmationUsesTheThemeAndCancelKeepsTheSession() throws Exception {
        account("user", "es"); Activity account = open(LoginActivity.class);
        try {
            View logout = find(account.getWindow().getDecorView(), "Cerrar sesión");
            assertNotNull(logout); getInstrumentation().runOnMainSync(logout::performClick);
            getInstrumentation().waitForIdleSync();
            var active = getInstrumentation().getUiAutomation().getRootInActiveWindow();
            assertFalse(active.findAccessibilityNodeInfosByText("Cancelar").isEmpty());
            capture("Session-confirmation");
            try (var fd = getInstrumentation().getUiAutomation().executeShellCommand("input keyevent 4");
                    var input = new java.io.FileInputStream(fd.getFileDescriptor())) { while (input.read() != -1) {} }
            getInstrumentation().waitForIdleSync();
            assertEquals("Cancelling must not log out", fixture.getString("token"), new SessionStore(context, ORIGIN).token());
        } finally { close(account); }
    }

    private void assertButtonLabels(View view) {
        if (view instanceof android.widget.Button button) assertFalse("Every visible action needs its label", button.getText().toString().trim().isEmpty());
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) assertButtonLabels(group.getChildAt(i));
    }

    public void testAddLinkSheetKeepsActionAndQualityLabels() throws Exception {
        account("user", "es"); Activity activity = open(LibraryActivity.class);
        try {
            View add = find(activity.getWindow().getDecorView(), "  Agregar enlace"); assertNotNull(add);
            getInstrumentation().runOnMainSync(add::performClick);
            long ready = System.currentTimeMillis() + 3000;
            android.view.accessibility.AccessibilityNodeInfo root;
            do { Thread.sleep(100); root = getInstrumentation().getUiAutomation().getRootInActiveWindow(); }
            while ((root == null || root.findAccessibilityNodeInfosByText("Solo audio").isEmpty()) && System.currentTimeMillis() < ready);
            assertNotNull(root);
            for (String label : new String[] {"Agregar", "Video", "Solo audio", "Pegar"}) assertFalse(label, root.findAccessibilityNodeInfosByText(label).isEmpty());
            capture("Add-link-sheet");
            try (var fd = getInstrumentation().getUiAutomation().executeShellCommand("input keyevent 4");
                    var input = new java.io.FileInputStream(fd.getFileDescriptor())) { while (input.read() != -1) {} }
            getInstrumentation().waitForIdleSync();
        } finally { close(activity); }
    }

    private void waitFor(Activity a, String label) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (find(a.getWindow().getDecorView(), label) == null && System.currentTimeMillis() < end) { Thread.sleep(100); getInstrumentation().waitForIdleSync(); }
        assertNotNull(label, find(a.getWindow().getDecorView(), label));
    }
    private void capture(String name) throws Exception {
        getInstrumentation().waitForIdleSync(); Thread.sleep(120);
        var active = getInstrumentation().getUiAutomation().getRootInActiveWindow();
        long ready = System.currentTimeMillis() + 3000;
        while ((active == null || !context.getPackageName().contentEquals(active.getPackageName())) && System.currentTimeMillis() < ready) {
            Thread.sleep(100);
            active = getInstrumentation().getUiAutomation().getRootInActiveWindow();
        }
        assertNotNull("Active app window", active);
        assertEquals("Capture must show the app, not an external dialog", context.getPackageName(), active.getPackageName().toString());
        File folder = context.getExternalFilesDir("ui-previews-v021"); folder.mkdirs();
        Bitmap shot = getInstrumentation().getUiAutomation().takeScreenshot(); assertNotNull(shot);
        try (FileOutputStream stream = new FileOutputStream(new File(folder, name + ".png"))) { shot.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        shot.recycle();
    }
    private View find(View view, String text) {
        if (view instanceof TextView label && text.equals(label.getText().toString())) return view;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { View match = find(group.getChildAt(i), text); if (match != null) return match; }
        return null;
    }
    private View description(View view, String text) {
        if (text.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { View match = description(group.getChildAt(i), text); if (match != null) return match; }
        return null;
    }
    private ScrollView scroll(View view) {
        if (view instanceof ScrollView scroll) return scroll;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { ScrollView match = scroll(group.getChildAt(i)); if (match != null) return match; }
        return null;
    }
}
