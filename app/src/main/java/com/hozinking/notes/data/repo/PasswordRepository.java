package com.hozinking.notes.data.repo;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;

import com.hozinking.notes.data.db.PasswordDao;
import com.hozinking.notes.data.db.PasswordEntry;
import com.hozinking.notes.data.db.VaultDatabase;
import com.hozinking.notes.security.PasswordCrypto;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Repository brankas kata sandi.
 *
 * <p>Enkripsi dilakukan di sini (bukan di UI): UI hanya memegang {@code char[]}
 * sementara, repository mengubahnya jadi blob terenkripsi sebelum insert/update,
 * dan me-wipe {@code char[]} segera setelah dipakai.
 */
public class PasswordRepository {
    private static volatile PasswordRepository instance;

    public interface Cb<T> {
        void onResult(T v);
    }

    private final Context appCtx;
    private final PasswordDao dao;
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());

    private PasswordRepository(Context ctx) {
        appCtx = ctx.getApplicationContext();
        dao = VaultDatabase.get(appCtx).passwordDao();
    }

    public static PasswordRepository get(Context ctx) {
        if (instance == null) {
            synchronized (PasswordRepository.class) {
                if (instance == null) {
                    instance = new PasswordRepository(ctx);
                }
            }
        }
        return instance;
    }

    private <T> void post(Cb<T> cb, T v) {
        if (cb != null) {
            main.post(() -> cb.onResult(v));
        }
    }

    public LiveData<List<PasswordEntry>> observeAll() {
        return dao.observeAll();
    }

    public LiveData<List<PasswordEntry>> search(String q) {
        return dao.search(q == null ? "" : q);
    }

    /**
     * Simpan entri baru / perbarui entri. Secret & notes dienkripsi di sini;
     * {@code char[]} input di-wipe setelah dipakai.
     */
    public void save(PasswordEntry e, char[] secret, char[] notes, Cb<Long> cb) {
        io.execute(() -> {
            long now = System.currentTimeMillis();
            try {
                e.encSecret = PasswordCrypto.encryptSecret(appCtx, secret);
                e.encNotes = (notes != null && notes.length > 0)
                        ? PasswordCrypto.encryptSecret(appCtx, notes) : null;
                if (e.id == 0) {
                    e.createdAt = now;
                    e.updatedAt = now;
                    long id = dao.insert(e);
                    post(cb, id);
                } else {
                    e.updatedAt = now;
                    dao.update(e);
                    post(cb, e.id);
                }
            } finally {
                PasswordCrypto.wipe(secret);
                PasswordCrypto.wipe(notes);
            }
        });
    }

    /** Dekripsi secret entri. Penelepon WAJIB wipe hasilnya via PasswordCrypto.wipe. */
    public void revealSecret(long id, Cb<char[]> cb) {
        io.execute(() -> {
            PasswordEntry e = dao.getById(id);
            char[] out = (e != null)
                    ? PasswordCrypto.decryptSecret(appCtx, e.encSecret)
                    : new char[0];
            if (e != null) {
                e.lastUsedAt = System.currentTimeMillis();
                dao.update(e);
            }
            post(cb, out);
        });
    }

    /** Dekripsi catatan rahasia entri. Penelepon WAJIB wipe hasilnya. */
    public void revealNotes(long id, Cb<char[]> cb) {
        io.execute(() -> {
            PasswordEntry e = dao.getById(id);
            char[] out = (e != null && e.encNotes != null)
                    ? PasswordCrypto.decryptSecret(appCtx, e.encNotes)
                    : new char[0];
            post(cb, out);
        });
    }

    /** Ambil entri mentah (tanpa dekripsi secret) — untuk prefill form edit. */
    public void getEntry(long id, Cb<PasswordEntry> cb) {
        io.execute(() -> post(cb, dao.getById(id)));
    }

    public void delete(long id, Cb<Void> cb) {        io.execute(() -> {
            dao.deleteById(id);
            post(cb, null);
        });
    }

    public void setFavorite(long id, boolean fav, Cb<Void> cb) {
        io.execute(() -> {
            PasswordEntry e = dao.getById(id);
            if (e != null) {
                e.favorite = fav;
                e.updatedAt = System.currentTimeMillis();
                dao.update(e);
            }
            post(cb, null);
        });
    }
}
