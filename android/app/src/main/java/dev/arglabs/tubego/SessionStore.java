package dev.arglabs.tubego;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Per-server encrypted sessions; AES key never leaves Android Keystore. */
public final class SessionStore {
    private static final String ALIAS="tubego.sessions.v1";
    private final SharedPreferences prefs;
    private final String namespace;
    public SessionStore(Context context, String origin) throws Exception {
        prefs=context.getSharedPreferences("tubego_sessions",Context.MODE_PRIVATE);
        namespace=Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(new ApiClient(origin).getBaseUrl().getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
    }
    private static synchronized SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey)store.getKey(ALIAS,null);
    }
    public void save(JSONObject session) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key());
        cipher.updateAAD(namespace.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted=cipher.doFinal(session.toString().getBytes(StandardCharsets.UTF_8));
        if(!prefs.edit().putString(namespace+".iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
                .putString(namespace+".ciphertext",Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit()) throw new Exception("No se pudo guardar la sesión");
    }
    public JSONObject read() throws Exception {
        String data=prefs.getString(namespace+".ciphertext",null);
        if(data==null) return null;
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(prefs.getString(namespace+".iv",""),Base64.NO_WRAP)));
        cipher.updateAAD(namespace.getBytes(StandardCharsets.UTF_8));
        return new JSONObject(new String(cipher.doFinal(Base64.decode(data,Base64.NO_WRAP)),StandardCharsets.UTF_8));
    }
    public String token() throws Exception { JSONObject session=read(); return session==null?null:session.getString("token"); }
    public void clear() { prefs.edit().remove(namespace+".iv").remove(namespace+".ciphertext").commit(); }
}
