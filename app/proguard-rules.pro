# Hozin Notes — R8 keep rules (v1.3+)

# --- SQLCipher native libs & DB layer ---
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }
-keep class com.hozinking.notes.data.db.** { *; }
-keep class * extends androidx.room.RoomDatabase

# --- NoteRepository: dipakai via refleksi (LocalBackupManager.insertImageRef) ---
-keep class com.hozinking.notes.data.repo.NoteRepository { *; }

# --- Google API client + Drive (Class.forName + GsonFactory reflection) ---
-keepattributes Signature,RuntimeVisibleAnnotations,AnnotationDefault
-keep class com.google.api.client.** { *; }
-keep class com.google.api.services.drive.** { *; }
-keep class com.google.common.** { *; }
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }
-dontwarn com.google.api.client.**
-dontwarn com.google.common.**

# --- WorkManager (reflection di inisialisasi) ---
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# --- Model JSON generik: jangan strip field yang dibaca via refleksi ---
-keepclassmembers class com.hozinking.notes.** {
    <fields>;
}

# --- Native methods ---
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Serializable ---
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
