package com.hozinking.notes.backup;

import android.content.Context;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.googleapis.media.MediaHttpUploaderProgressListener;
import com.google.api.client.http.FileContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.About;
import com.google.api.services.drive.model.FileList;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Backup ke Google Drive appDataFolder (folder tersembunyi khusus aplikasi,
 * tidak mengotori Drive user — ala backup WhatsApp).
 *
 * SEMUA runtime-guarded via {@link #isAvailable()}: panggil dulu sebelum pakai.
 * Method *Blocking dipanggil dari background thread / Worker, bukan main thread.
 */
public class DriveBackupManager {

    public static class DriveFile {
        public String id;
        public String name;
        public long modifiedTime;
        public long size;
    }

    /** Callback progres upload (dipanggil dari background thread). */
    public interface ProgressListener {
        /** fraction 0..1 saat upload media berjalan. */
        void onProgress(double fraction);
    }

    /** {terpakai, batas}; batas = -1 bila tidak diketahui. */
    public static class Quota {
        public long used;
        public long limit = -1;
    }

    /** true bila class Drive API + Google Sign-In tersedia di runtime. */
    public static boolean isAvailable() {
        try {
            Class.forName("com.google.api.services.drive.Drive");
            Class.forName("com.google.android.gms.auth.api.signin.GoogleSignIn");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** true bila sudah login Google (akun tersedia). false aman bila Play Services tak ada. */
    public static boolean isSignedIn(Context ctx) {
        try {
            GoogleSignInAccount acc = GoogleSignIn.getLastSignedInAccount(ctx);
            return acc != null && acc.getAccount() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private Drive getService(Context ctx) throws Exception {
        GoogleSignInAccount acc = GoogleSignIn.getLastSignedInAccount(ctx);
        if (acc == null || acc.getAccount() == null)
            throw new Exception("Belum login Google — hubungkan akun dulu di Pengaturan > Backup");
        GoogleAccountCredential cred = GoogleAccountCredential.usingOAuth2(
                ctx, Collections.singleton(DriveScopes.DRIVE_APPDATA));
        cred.setSelectedAccount(acc.getAccount());
        return new Drive.Builder(new NetHttpTransport(), new GsonFactory(), cred)
                .setApplicationName("Hozin Notes")
                .build();
    }

    /** Upload zip ke appDataFolder. Return file id. BLOCKING. */
    public String uploadBlocking(Context ctx, File zip) throws Exception {
        return uploadBlocking(ctx, zip, null);
    }

    /** Upload dengan callback progres (fraction 0..1). BLOCKING. */
    public String uploadBlocking(Context ctx, File zip, ProgressListener pl) throws Exception {
        Drive svc = getService(ctx);
        com.google.api.services.drive.model.File meta = new com.google.api.services.drive.model.File();
        String name = "hozin_notes_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".zip";
        meta.setName(name);
        meta.setParents(Collections.singletonList("appDataFolder"));
        FileContent content = new FileContent("application/zip", zip);
        Drive.Files.Create create = svc.files().create(meta, content).setFields("id");
        if (pl != null) {
            MediaHttpUploader uploader = create.getMediaHttpUploader();
            uploader.setProgressListener((MediaHttpUploaderProgressListener) u -> {
                if (u.getUploadState() == MediaHttpUploader.UploadState.MEDIA_IN_PROGRESS) {
                    try {
                        pl.onProgress(u.getProgress());
                    } catch (Exception ignored) {
                    }
                } else if (u.getUploadState() == MediaHttpUploader.UploadState.MEDIA_COMPLETE) {
                    try {
                        pl.onProgress(1.0);
                    } catch (Exception ignored) {
                    }
                }
            });
        }
        com.google.api.services.drive.model.File created = create.execute();
        if (created == null || created.getId() == null)
            throw new Exception("Upload Drive gagal (id kosong)");
        return created.getId();
    }

    /** Kuota penyimpanan Drive user. BLOCKING. */
    public Quota getQuotaBlocking(Context ctx) throws Exception {
        Drive svc = getService(ctx);
        About about = svc.about().get().setFields("storageQuota").execute();
        Quota q = new Quota();
        if (about != null && about.getStorageQuota() != null) {
            Long used = about.getStorageQuota().getUsage();
            Long limit = about.getStorageQuota().getLimit();
            q.used = used != null ? used : 0;
            q.limit = limit != null ? limit : -1;
        }
        return q;
    }

    /** Daftar backup di appDataFolder, terbaru dulu. BLOCKING. */
    public List<DriveFile> listBlocking(Context ctx) throws Exception {
        Drive svc = getService(ctx);
        FileList fl = svc.files().list()
                .setSpaces("appDataFolder")
                .setOrderBy("modifiedTime desc")
                .setFields("files(id,name,modifiedTime,size)")
                .execute();
        List<DriveFile> out = new ArrayList<>();
        if (fl.getFiles() != null) {
            for (com.google.api.services.drive.model.File f : fl.getFiles()) {
                DriveFile d = new DriveFile();
                d.id = f.getId();
                d.name = f.getName();
                d.modifiedTime = f.getModifiedTime() != null ? f.getModifiedTime().getValue() : 0L;
                d.size = f.getSize() != null ? f.getSize() : 0L;
                out.add(d);
            }
        }
        return out;
    }

    /** Download satu file backup ke dest. BLOCKING. */
    public boolean downloadBlocking(Context ctx, String fileId, File dest) throws Exception {
        Drive svc = getService(ctx);
        File parent = dest.getParentFile();
        if (parent != null) parent.mkdirs();
        try (OutputStream out = new FileOutputStream(dest)) {
            svc.files().get(fileId).executeMediaAndDownloadTo(out);
        }
        return dest.isFile() && dest.length() > 0;
    }

    /** Sisakan `keep` backup terbaru, hapus sisanya. BLOCKING. */
    public void pruneBlocking(Context ctx, int keep) throws Exception {
        List<DriveFile> files = listBlocking(ctx);
        Drive svc = getService(ctx);
        for (int i = Math.max(0, keep); i < files.size(); i++) {
            svc.files().delete(files.get(i).id).execute();
        }
    }
}
