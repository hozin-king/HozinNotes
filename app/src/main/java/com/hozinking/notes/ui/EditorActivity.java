package com.hozinking.notes.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Html;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.CharacterStyle;
import android.text.style.ParagraphStyle;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.util.Linkify;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.hozinking.notes.BuildConfig;
import com.hozinking.notes.R;
import com.hozinking.notes.data.db.AudioRefEntity;
import com.hozinking.notes.data.db.FolderEntity;
import com.hozinking.notes.data.db.ImageRefEntity;
import com.hozinking.notes.data.db.NoteEntity;
import com.hozinking.notes.data.db.NoteRevision;
import com.hozinking.notes.data.db.TagEntity;
import com.hozinking.notes.data.repo.NoteRepository;
import com.hozinking.notes.ui.dialog.IosActionSheet;
import com.hozinking.notes.ui.dialog.IosDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Editor catatan. Extra "noteId" (long, -1 = baru).
 * Auto-save debounce 800ms + flush di onPause. Gambar via galeri/kamera
 * disimpan terenkripsi lewat repo. Checklist: "+ Item" & "Centang".
 */
public class EditorActivity extends AppCompatActivity {

    private EditText titleInput;
    private EditText contentInput;
    private LinearLayout imageStrip;
    private View btnUndo;
    private View btnRedo;
    private UndoRedoManager undoRedo;

    private long noteId = -1;
    private NoteEntity note;
    private boolean loading = true;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler saveHandler = new Handler(Looper.getMainLooper());
    private final Runnable saveRunnable = this::saveNow;

    private Uri cameraUri;
    private Uri liveVideoUri;
    private NoteSpeaker speaker; // v1.17: bacakan catatan (TTS)
    private ActivityResultLauncher<String> galleryLauncher;
    private ActivityResultLauncher<Uri> cameraLauncher;
    private ActivityResultLauncher<Intent> liveVideoLauncher;
    private ActivityResultLauncher<Intent> viewerLauncher;
    private TextView imageHeader;

    // Rekaman suara (audio notes)
    private LinearLayout audioList;
    private TextView audioHeader;
    private TextView audioBtnLabel;
    private ActivityResultLauncher<String> audioPermLauncher;
    private MediaRecorder recorder;
    private File recTempFile;
    private long recStartMs;
    private long recNoteId = -1;
    private MediaPlayer player;
    private AudioRefEntity playingRef;
    private ImageButton playingBtn;
    private File playTempFile;

    private PopupWindow livePreviewPopup;
    private VideoView livePreviewVideo;
    private File livePreviewTemp;
    private final Handler livePressHandler = new Handler(Looper.getMainLooper());
    private Runnable livePressRunnable;

    // Format toolbar (action mode seleksi)
    private static final int MENU_BOLD = 1101;
    private static final int MENU_ITALIC = 1102;
    private static final int MENU_STRIKE = 1103;
    private static final int MENU_HL = 1104;
    private static final int HIGHLIGHT_COLOR = 0xFFFFFF00; // kuning stabilo
    private static final int RC_PIN_SETUP = 2001;

    // Tap detection (checklist & tabel)
    private float tapX, tapY;
    private long tapT;

    // Cari dalam catatan
    private ScrollView editorScroll;
    private LinearLayout findBar;
    // v1.23: baris ganti teks (cari & ganti), disembunyikan default.
    private LinearLayout replaceBar;
    private EditText replaceInput;

    // Sketsa & pratinjau
    private TextView previewContent;
    private TextView previewLabel;
    private View titleDivider;
    private boolean previewMode = false;
    // v1.22: kecerahan layar khusus mode pratinjau (konsep brightness kustom
    // di reading mode ala alialbaali/Noto, Apache-2.0 — implementasi sendiri).
    private View previewBrightnessRow;
    private SeekBar previewBrightness;
    private TextView previewBrightnessPct;
    private static final String PREFS_UI = "notes_ui_prefs";
    private static final String KEY_PREVIEW_BRIGHTNESS = "preview_brightness_pct";
    private static final int BRIGHTNESS_SYSTEM = -1;
    private ActivityResultLauncher<Intent> sketchLauncher;    private EditText findInput;
    private TextView findCount;
    private TextView wordCountView;
    private final List<int[]> findMatches = new ArrayList<>();
    private int findIndex = -1;
    private static final int FIND_MATCH_COLOR = 0x66FFEB3B;
    private static final int FIND_CURRENT_COLOR = 0xFFFF9800;

    /** Span khusus highlight hasil pencarian — selalu dibersihkan sebelum simpan. */
    private static class FindSpan extends BackgroundColorSpan {
        FindSpan(int color) {
            super(color);
        }
    }

    // List bernomor otomatis
    private boolean numGuard = false;
    private int pendingNl = -1;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);

        titleInput = findViewById(R.id.title_input);
        contentInput = findViewById(R.id.content_input);
        imageStrip = findViewById(R.id.image_strip);
        imageHeader = findViewById(R.id.image_header);

        // URL bisa di-tap langsung dari editor (tanpa merusak kursor/seleksi).
        Linkify.addLinks(contentInput, Linkify.WEB_URLS);
        contentInput.setMovementMethod(LinkTouchMovementMethod.getInstance());
        // Ukuran huruf editor mengikuti preferensi (Pengaturan → Tampilan).
        contentInput.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                SettingsActivity.editorFontSize(this));

        galleryLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(), uri -> {
                    if (uri != null) {
                        Uri u = uri;
                        withNoteId(id -> NoteRepository.get(this)
                                .addImageFromUri(this, id, u,
                                        img -> main.post(this::refreshImages)));
                    }
                });
        cameraLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(), ok -> {
                    if (Boolean.TRUE.equals(ok) && cameraUri != null) {
                        Uri u = cameraUri;
                        withNoteId(id -> NoteRepository.get(this)
                                .addImageFromUri(this, id, u,
                                        img -> main.post(this::refreshImages)));
                    }
                });
        liveVideoLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && liveVideoUri != null) {
                        Uri u = liveVideoUri;
                        withNoteId(id -> NoteRepository.get(this)
                                .addLivePhotoFromVideo(this, id, u,
                                        img -> main.post(() -> {
                                            refreshImages();
                                            if (img != null) {
                                                Toast.makeText(this,
                                                        R.string.live_saved,
                                                        Toast.LENGTH_SHORT).show();
                                            } else {
                                                Toast.makeText(this,
                                                        R.string.live_failed,
                                                        Toast.LENGTH_SHORT).show();
                                            }
                                        })));
                    }
                });

        SimpleTextWatcher watcher = new SimpleTextWatcher(s -> {
            scheduleSave();
            updateWordCount();
            if (findBar != null && findBar.getVisibility() == View.VISIBLE) runFind();
        });
        titleInput.addTextChangedListener(watcher);
        contentInput.addTextChangedListener(watcher);

        setupFormatActionMode();
        setupTapHandling();
        setupNumberedList();
        setupUndoRedo();

        findViewById(R.id.btn_checklist_add).setOnClickListener(v -> addChecklistItem());
        findViewById(R.id.btn_checklist_toggle).setOnClickListener(v -> toggleChecklist());
        findViewById(R.id.btn_gallery).setOnClickListener(v -> galleryLauncher.launch("image/*"));
        findViewById(R.id.btn_camera).setOnClickListener(v -> launchCamera());
        findViewById(R.id.btn_live).setOnClickListener(v -> launchLivePhoto());
        findViewById(R.id.btn_menu).setOnClickListener(v -> showMenu());

        // Rekaman suara: tombol toolbar + daftar rekaman di bawah strip gambar.
        audioList = findViewById(R.id.audio_list);
        audioHeader = findViewById(R.id.audio_header);
        audioBtnLabel = findViewById(R.id.tv_audio);
        audioPermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (Boolean.TRUE.equals(granted)) {
                        startRecording();
                    } else {
                        Toast.makeText(this, R.string.audio_perm_denied,
                                Toast.LENGTH_LONG).show();
                    }
                });
        findViewById(R.id.btn_audio).setOnClickListener(v -> onAudioButton());

        // Sketsa: gambar bebas lalu simpan sebagai gambar catatan.
        sketchLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        refreshImages();
                        Toast.makeText(this, R.string.sketch_saved,
                                Toast.LENGTH_SHORT).show();
                    }
                });
        findViewById(R.id.btn_sketch).setOnClickListener(v -> openSketch());

        // Pratinjau: mode baca read-only dari isi editor.
        previewContent = findViewById(R.id.preview_content);
        previewLabel = findViewById(R.id.tv_preview);
        titleDivider = findViewById(R.id.title_divider);
        findViewById(R.id.btn_preview).setOnClickListener(v -> togglePreview());

        // v1.22: slider kecerahan — hanya berlaku di jendela editor ini,
        // tidak mengubah kecerahan sistem. Nilai disimpan per preferensi.
        previewBrightnessRow = findViewById(R.id.preview_brightness_row);
        previewBrightness = findViewById(R.id.preview_brightness);
        previewBrightnessPct = findViewById(R.id.preview_brightness_pct);
        if (previewBrightness != null) {
            previewBrightness.setOnSeekBarChangeListener(
                    new SeekBar.OnSeekBarChangeListener() {
                        @Override
                        public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                            if (!fromUser) return;
                            applyPreviewBrightness(p);
                            getSharedPreferences(PREFS_UI, MODE_PRIVATE)
                                    .edit().putInt(KEY_PREVIEW_BRIGHTNESS, p).apply();
                            if (previewBrightnessPct != null) {
                                previewBrightnessPct.setText(
                                        getString(R.string.preview_brightness_pct, p));
                            }
                        }

                        @Override public void onStartTrackingTouch(SeekBar s) {
                        }

                        @Override public void onStopTrackingTouch(SeekBar s) {
                        }
                    });
        }

        // Bar cari dalam catatan + penghitung kata
        editorScroll = findViewById(R.id.editor_scroll);
        findBar = findViewById(R.id.find_bar);
        findInput = findViewById(R.id.find_input);
        findCount = findViewById(R.id.find_count);
        wordCountView = findViewById(R.id.word_count);
        findInput.addTextChangedListener(new SimpleTextWatcher(s -> runFind()));
        findViewById(R.id.btn_find_prev).setOnClickListener(v -> findStep(-1));
        findViewById(R.id.btn_find_next).setOnClickListener(v -> findStep(1));
        findViewById(R.id.btn_find_close).setOnClickListener(v -> hideFindBar());
        // v1.23: cari & ganti.
        replaceBar = findViewById(R.id.replace_bar);
        replaceInput = findViewById(R.id.replace_input);
        findViewById(R.id.btn_find_replace_toggle)
                .setOnClickListener(v -> toggleReplaceBar());
        findViewById(R.id.btn_replace_one).setOnClickListener(v -> replaceCurrent());
        findViewById(R.id.btn_replace_all).setOnClickListener(v -> replaceAll());
        updateWordCount();

        noteId = getIntent().getLongExtra("noteId", -1);
        if (noteId != -1) {
            NoteRepository.get(this).getNote(noteId, n -> main.post(() -> {
                if (n == null) {
                    finish();
                    return;
                }
                note = n;
                titleInput.setText(n.title == null ? "" : n.title);
                setContentFromNote(n);
                loading = false;
                undoRedo.clear();
                undoRedo.setTracking(true);
                updateUndoRedoButtons();
                refreshImages();
                refreshAudio();
                // v1.14: dibuka dari hasil pencarian — tampilkan bar cari dengan
                // kata kunci pencarian terisi agar cocok langsung tersorot.
                autoOpenFindFromSearch();
            }));
        } else {
            note = newNote();
            // Template dari tahan-tombol + FAB — hanya saat pertama dibuat,
            // bukan saat recreate (rotasi) agar tidak menimpa ketikan user.
            if (savedInstanceState == null) applyTemplateExtras();
            loading = false;
            undoRedo.clear();
            undoRedo.setTracking(true);
            updateUndoRedoButtons();
        }

        // Terima konten yang di-share dari aplikasi lain (ACTION_SEND).
        handleSharedContent(getIntent());
    }

    private NoteEntity newNote() {
        NoteEntity n = new NoteEntity();
        n.title = "";
        n.content = "";
        n.color = 0;
        long now = System.currentTimeMillis();
        n.createdAt = now;
        n.updatedAt = now;
        return n;
    }

    /** Isi judul/isi dari template (extra "templateTitle"/"templateContent"). */
    private void applyTemplateExtras() {
        String t = getIntent().getStringExtra("templateTitle");
        String c = getIntent().getStringExtra("templateContent");
        if (t == null && c == null) return;
        if (t != null) {
            note.title = t;
            titleInput.setText(t);
        }
        if (c != null) {
            note.content = c;
            contentInput.setText(c);
        }
    }

    // ---------- auto-save ----------

    private void scheduleSave() {
        if (loading) return;
        saveHandler.removeCallbacks(saveRunnable);
        saveHandler.postDelayed(saveRunnable, 800);
    }

    private void syncFieldsToNote() {
        // Highlight pencarian tidak boleh ikut tersimpan ke DB/HTML.
        boolean findVisible = findBar != null
                && findBar.getVisibility() == View.VISIBLE;
        clearFindHighlights();
        note.title = titleInput.getText().toString();
        Editable e = contentInput.getText();
        if (hasRichContent(e)) {
            note.contentHtml = true;
            note.content = toHtmlWithTables(e);
        } else {
            note.contentHtml = false;
            note.content = e.toString();
        }
        if (findVisible) runFind(); // gambar ulang highlight setelah simpan
    }

    private void saveNow() {
        if (loading || note == null) return;
        syncFieldsToNote();
        boolean empty = note.title.trim().isEmpty() && note.content.trim().isEmpty();
        if (noteId == -1 && empty) return; // jangan buat note kosong
        note.updatedAt = System.currentTimeMillis();
        if (noteId == -1) {
            NoteRepository.get(this).insertNote(note, id -> main.post(() -> {
                noteId = id;
                note.id = id;
                refreshImages();
                // v1.23: snapshot revisi pertama.
                NoteRepository.get(EditorActivity.this).saveRevisionIfChanged(
                        id, note.title, note.content, note.contentHtml);
            }));
        } else {
            NoteRepository.get(this).updateNote(note, v -> {
            });
            // v1.23: snapshot revisi bila isi/judul berubah (diabaikan bila sama).
            NoteRepository.get(this).saveRevisionIfChanged(
                    noteId, note.title, note.content, note.contentHtml);
        }
    }

    /** Pastikan note sudah tersimpan (punya id) sebelum operasi yang butuh id. */
    private void withNoteId(NoteRepository.Cb<Long> cb) {
        if (note == null) return;
        if (noteId != -1) {
            cb.onResult(noteId);
            return;
        }
        saveHandler.removeCallbacks(saveRunnable);
        syncFieldsToNote();
        note.updatedAt = System.currentTimeMillis();
        NoteRepository.get(this).insertNote(note, id -> main.post(() -> {
            noteId = id;
            note.id = id;
            cb.onResult(id);
        }));
    }

    @Override
    protected void onPause() {
        saveHandler.removeCallbacks(saveRunnable);
        saveNow();
        if (recorder != null) {
            // Jangan biarkan rekaman jalan saat activity tidak terlihat.
            stopRecording();
        }
        stopPlayback();
        dismissLivePreview();
        if (speaker != null) speaker.stop(); // v1.17: hentikan bacaan TTS
        com.hozinking.notes.widget.NoteWidgetProvider.refreshAll(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (speaker != null) {
            speaker.shutdown();
            speaker = null;
        }
        super.onDestroy();
    }

    // ---------- checklist ----------

    private void addChecklistItem() {
        int pos = Math.max(contentInput.getSelectionStart(), 0);
        Editable e = contentInput.getText();
        String prefix = (pos > 0 && e.charAt(pos - 1) != '\n') ? "\n" : "";
        e.insert(pos, prefix + "- [ ] ");
        scheduleSave();
    }

    /**
     * Sisipkan cap tanggal &amp; waktu sekarang di posisi kursor.
     * Format ala Indonesia, mis. "15 Okt 2026 09:12".
     */
    private void insertDateTime() {
        if (previewMode) togglePreview();
        if (note == null) return;
        String stamp = new java.text.SimpleDateFormat("d MMM yyyy HH:mm",
                new Locale("id", "ID")).format(new java.util.Date());
        int pos = Math.max(contentInput.getSelectionStart(), 0);
        Editable e = contentInput.getText();
        String prefix = (pos > 0 && e.charAt(pos - 1) != '\n') ? "\n" : "";
        e.insert(pos, prefix + stamp);
        scheduleSave();
    }

    /**
     * v1.28: sisipkan garis pemisah "---" sebagai barisnya sendiri di
     * posisi kursor (pola yang sama dengan insertDateTime). Bila kursor
     * di tengah baris, baris itu dibelah: pemisah di baris sendiri,
     * sisa teks pindah ke baris berikutnya; kursor berakhir di baris
     * kosong di bawah pemisah sehingga pengetikan bisa langsung lanjut.
     */
    private void insertSeparator() {
        if (previewMode) togglePreview();
        if (note == null) return;
        int pos = Math.max(contentInput.getSelectionStart(), 0);
        Editable e = contentInput.getText();
        String prefix = (pos > 0 && e.charAt(pos - 1) != '\n') ? "\n" : "";
        e.insert(pos, prefix + "---\n");
        scheduleSave();
        updateWordCount();
    }

    private void toggleChecklist() {
        int pos = Math.max(contentInput.getSelectionStart(), 0);
        Editable e = contentInput.getText();
        String s = e.toString();
        int lineStart = s.lastIndexOf('\n', pos - 1) + 1;
        int lineEnd = s.indexOf('\n', pos);
        if (lineEnd < 0) lineEnd = s.length();
        String line = s.substring(lineStart, lineEnd);
        String next;
        if (line.contains("[ ]")) {
            next = line.replaceFirst("\\[ \\]", "[x]");
        } else if (line.contains("[x]")) {
            next = line.replaceFirst("\\[x\\]", "[ ]");
        } else {
            next = "- [ ] " + line;
        }
        e.replace(lineStart, lineEnd, next);
        scheduleSave();
    }

    // ---------- format teks via menu seleksi ----------

    private void setupFormatActionMode() {
        contentInput.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                menu.add(0, MENU_BOLD, 0, getString(R.string.fmt_bold));
                menu.add(0, MENU_ITALIC, 1, getString(R.string.fmt_italic));
                menu.add(0, MENU_STRIKE, 2, getString(R.string.fmt_strike));
                menu.add(0, MENU_HL, 3, getString(R.string.fmt_highlight));
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                int a = contentInput.getSelectionStart();
                int b = contentInput.getSelectionEnd();
                int s = Math.min(a, b), e = Math.max(a, b);
                if (s < 0 || e <= s) return false;
                switch (item.getItemId()) {
                    case MENU_BOLD:
                        toggleStyleSpan(s, e, Typeface.BOLD);
                        break;
                    case MENU_ITALIC:
                        toggleStyleSpan(s, e, Typeface.ITALIC);
                        break;
                    case MENU_STRIKE:
                        toggleStrikeSpan(s, e);
                        break;
                    case MENU_HL:
                        toggleHighlightSpan(s, e);
                        break;
                    default:
                        return false;
                }
                mode.finish();
                scheduleSave();
                return true;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
            }
        });
        viewerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> main.post(this::refreshImages));
    }

    private void toggleStyleSpan(int s, int e, int style) {
        Editable ed = contentInput.getText();
        StyleSpan[] spans = ed.getSpans(s, e, StyleSpan.class);
        boolean found = false;
        for (StyleSpan sp : spans) {
            if (sp.getStyle() == style) {
                ed.removeSpan(sp);
                found = true;
            }
        }
        if (!found) {
            ed.setSpan(new StyleSpan(style), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void toggleStrikeSpan(int s, int e) {
        Editable ed = contentInput.getText();
        StrikethroughSpan[] spans = ed.getSpans(s, e, StrikethroughSpan.class);
        if (spans.length > 0) {
            for (StrikethroughSpan sp : spans) ed.removeSpan(sp);
        } else {
            ed.setSpan(new StrikethroughSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void toggleHighlightSpan(int s, int e) {
        Editable ed = contentInput.getText();
        BackgroundColorSpan[] spans = ed.getSpans(s, e, BackgroundColorSpan.class);
        boolean found = false;
        for (BackgroundColorSpan sp : spans) {
            if (sp.getBackgroundColor() == HIGHLIGHT_COLOR) {
                ed.removeSpan(sp);
                found = true;
            }
        }
        if (!found) {
            ed.setSpan(new BackgroundColorSpan(HIGHLIGHT_COLOR),
                    s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    // ---------- tap: checklist & tabel ----------

    private void setupTapHandling() {
        contentInput.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                tapX = ev.getX();
                tapY = ev.getY();
                tapT = System.currentTimeMillis();
                return false;
            }
            if (ev.getAction() == MotionEvent.ACTION_UP) {
                float dx = ev.getX() - tapX, dy = ev.getY() - tapY;
                if (System.currentTimeMillis() - tapT < 350 && dx * dx + dy * dy < 900) {
                    if (handleTap(ev.getX(), ev.getY())) return true;
                }
            }
            return false;
        });
    }

    private boolean handleTap(float x, float y) {
        Layout layout = contentInput.getLayout();
        if (layout == null) return false;
        int lx = (int) x - contentInput.getTotalPaddingLeft() + contentInput.getScrollX();
        int ly = (int) y - contentInput.getTotalPaddingTop() + contentInput.getScrollY();
        if (ly < 0) return false;
        int line;
        try {
            line = layout.getLineForVertical(ly);
        } catch (Exception e) {
            return false;
        }
        Editable ed = contentInput.getText();
        int lineStart = layout.getLineStart(line);
        int lineEnd = layout.getLineEnd(line);
        String lineText = ed.subSequence(lineStart, lineEnd).toString();

        // Ketuk kotak checklist "- [ ] " / "- [x] ".
        if (lineText.startsWith("- [ ] ") || lineText.startsWith("- [x] ")) {
            float markerRight = layout.getPrimaryHorizontal(
                    Math.min(lineStart + 6, lineEnd));
            if (lx <= markerRight) {
                toggleChecklistAtLine(lineStart, lineEnd, lineText);
                return true;
            }
        }
        // Ketuk kartu tabel.
        int off = layout.getOffsetForHorizontal(line, lx);
        TableSpan[] ts = ed.getSpans(off, off, TableSpan.class);
        if (ts.length > 0) {
            openTableEditor(ts[0]);
            return true;
        }
        return false;
    }

    private void toggleChecklistAtLine(int lineStart, int lineEnd, String lineText) {
        Editable ed = contentInput.getText();
        // getLineEnd() menyertakan '\n' — jangan ikut terhapus.
        boolean hasNl = lineText.endsWith("\n");
        String body = hasNl ? lineText.substring(0, lineText.length() - 1) : lineText;
        int end = hasNl ? lineEnd - 1 : lineEnd;
        String next;
        if (body.startsWith("- [ ] ")) {
            next = "- [x] " + body.substring(6);
        } else {
            next = "- [ ] " + body.substring(6);
        }
        ed.replace(lineStart, end, next);
        scheduleSave();
    }

    // ---------- aksi checklist massal ----------
    // Terinspirasi aksi task-list NotallyX ("remove checked", "sort checked to end") —
    // implementasi sendiri murni manipulasi teks, tanpa dependensi baru.

    private static int firstNonSpace(String line) {
        int i = 0;
        while (i < line.length()
                && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return i;
    }

    private static boolean isChecklistLine(String line) {
        String t = line.substring(firstNonSpace(line));
        return t.startsWith("- [ ]") || t.startsWith("- [x]");
    }

    private static boolean isCheckedLine(String line) {
        return line.substring(firstNonSpace(line)).startsWith("- [x]");
    }

    /** Dalam tiap blok checklist berurutan, pindahkan item yang dicentang ke bawah. */
    private void sortCheckedToBottom() {
        String s = contentInput.getText().toString();
        if (!s.contains("[x]")) {
            Toast.makeText(this, R.string.checklist_none_checked, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] lines = s.split("\n", -1);
        StringBuilder out = new StringBuilder(s.length());
        List<String> unchecked = new ArrayList<>();
        List<String> checked = new ArrayList<>();
        for (int i = 0; i <= lines.length; i++) {
            String line = i < lines.length ? lines[i] : null;
            if (line != null && isChecklistLine(line)) {
                (isCheckedLine(line) ? checked : unchecked).add(line);
                continue;
            }
            for (String l : unchecked) out.append(l).append('\n');
            for (String l : checked) out.append(l).append('\n');
            unchecked.clear();
            checked.clear();
            if (line != null) out.append(line).append('\n');
        }
        if (out.length() > 0) out.setLength(out.length() - 1);
        String next = out.toString();
        if (next.equals(s)) {
            Toast.makeText(this, R.string.checklist_none_checked, Toast.LENGTH_SHORT).show();
            return;
        }
        contentInput.getText().replace(0, contentInput.length(), next);
        scheduleSave();
    }

    /** Hapus semua baris checklist yang sudah dicentang. Bisa di-undo. */
    private void clearCheckedItems() {
        String s = contentInput.getText().toString();
        if (!s.contains("[x]")) {
            Toast.makeText(this, R.string.checklist_none_checked, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] lines = s.split("\n", -1);
        StringBuilder out = new StringBuilder(s.length());
        int removed = 0;
        for (String line : lines) {
            if (isChecklistLine(line) && isCheckedLine(line)) {
                removed++;
                continue;
            }
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        if (removed == 0) {
            Toast.makeText(this, R.string.checklist_none_checked, Toast.LENGTH_SHORT).show();
            return;
        }
        contentInput.getText().replace(0, contentInput.length(), out.toString());
        scheduleSave();
        Toast.makeText(this, getString(R.string.checklist_cleared, removed),
                Toast.LENGTH_SHORT).show();
    }

    // ---------- list bernomor otomatis ----------

    private void setupNumberedList() {
        contentInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (numGuard) return;
                if (count == 1 && before == 0 && start < s.length()
                        && s.charAt(start) == '\n') {
                    pendingNl = start;
                } else {
                    pendingNl = -1;
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (numGuard || pendingNl < 0 || pendingNl >= s.length()) {
                    pendingNl = -1;
                    return;
                }
                int nlPos = pendingNl;
                pendingNl = -1;
                int prevStart = s.toString().lastIndexOf('\n', nlPos - 1) + 1;
                String prevLine = s.subSequence(prevStart, nlPos).toString();
                java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile("^(\\d+)\\.\\s").matcher(prevLine);
                if (!m.find()) return;
                numGuard = true;
                try {
                    String rest = prevLine.substring(m.end());
                    if (rest.trim().isEmpty()) {
                        // Baris nomor dikosongkan -> keluar dari mode list.
                        s.delete(prevStart, prevStart + m.end());
                    } else {
                        int n = Integer.parseInt(m.group(1)) + 1;
                        s.insert(nlPos + 1, n + ". ");
                    }
                } catch (Exception ignored) {
                } finally {
                    numGuard = false;
                }
                scheduleSave();
            }
        });
    }

    // ---------- undo / redo ----------

    private void setupUndoRedo() {
        undoRedo = new UndoRedoManager(contentInput);
        undoRedo.setTracking(false); // aktif setelah konten dimuat
        undoRedo.setListener((canUndo, canRedo) -> updateUndoRedoButtons());
        btnUndo = findViewById(R.id.btn_undo);
        btnRedo = findViewById(R.id.btn_redo);
        btnUndo.setOnClickListener(v -> {
            if (undoRedo.canUndo()) undoRedo.undo();
        });
        btnRedo.setOnClickListener(v -> {
            if (undoRedo.canRedo()) undoRedo.redo();
        });
        updateUndoRedoButtons();
    }

    private void updateUndoRedoButtons() {
        if (btnUndo == null || btnRedo == null || undoRedo == null) return;
        boolean cu = undoRedo.canUndo();
        boolean cr = undoRedo.canRedo();
        btnUndo.setEnabled(cu);
        btnUndo.setAlpha(cu ? 1f : 0.35f);
        btnRedo.setEnabled(cr);
        btnRedo.setAlpha(cr ? 1f : 0.35f);
    }

    // ---------- konten HTML (teks berformat) ----------

    private void setContentFromNote(NoteEntity n) {
        String c = n.content == null ? "" : n.content;
        if (!n.contentHtml) {
            contentInput.setText(c);
            return;
        }
        try {
            // Keluarkan blok <table> -> placeholder + TableSpan (tak selamat via fromHtml).
            SpannableStringBuilderSafe sb = new SpannableStringBuilderSafe();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?s)<table.*?</table>").matcher(c);
            int last = 0;
            while (m.find()) {
                sb.append(Html.fromHtml(c.substring(last, m.start()),
                        Html.FROM_HTML_MODE_LEGACY));
                String thtml = m.group();
                int st = sb.length();
                sb.append("￼");
                sb.setSpan(new TableSpan(thtml, TableSpan.countRows(thtml),
                        TableSpan.countCols(thtml)), st, st + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                last = m.end();
            }
            sb.append(Html.fromHtml(c.substring(last), Html.FROM_HTML_MODE_LEGACY));
            contentInput.setText(sb.build());
        } catch (Exception e) {
            contentInput.setText(c); // fallback: tampilkan mentah
        }
    }

    private static boolean hasRichContent(Editable e) {
        for (Object sp : e.getSpans(0, e.length(), Object.class)) {
            if (sp instanceof android.text.style.URLSpan) continue; // linkify: tetap plain
            if (sp instanceof CharacterStyle || sp instanceof ParagraphStyle
                    || sp instanceof TableSpan) {
                return true;
            }
        }
        return false;
    }

    private static String toHtmlWithTables(Editable e) {
        TableSpan[] tables = e.getSpans(0, e.length(), TableSpan.class);
        java.util.Arrays.sort(tables,
                (a, b) -> Integer.compare(e.getSpanStart(a), e.getSpanStart(b)));
        String html = Html.toHtml(e, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE);
        for (TableSpan t : tables) {
            html = html.replaceFirst("￼",
                    java.util.regex.Matcher.quoteReplacement(t.html));
        }
        return html;
    }

    /** StringBuilder aman untuk menampung Spanned + span kustom. */
    private static class SpannableStringBuilderSafe {
        private final android.text.SpannableStringBuilder sb =
                new android.text.SpannableStringBuilder();

        void append(CharSequence cs) {
            sb.append(cs);
        }

        void setSpan(Object sp, int s, int e, int flags) {
            sb.setSpan(sp, s, e, flags);
        }

        int length() {
            return sb.length();
        }

        android.text.SpannableStringBuilder build() {
            return sb;
        }
    }

    // ---------- tabel ----------

    private void showInsertTableDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad / 2, pad, pad / 2);

        EditText etRows = new EditText(this);
        etRows.setHint(getString(R.string.table_rows));
        etRows.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etRows.setText("2");
        EditText etCols = new EditText(this);
        etCols.setHint(getString(R.string.table_cols));
        etCols.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etCols.setText("2");
        root.addView(etRows);
        root.addView(etCols);

        new AlertDialog.Builder(this)
                .setTitle(R.string.table_insert)
                .setView(root)
                .setPositiveButton(R.string.table_insert_ok, (d, w) -> {
                    int r = clamp(parseNum(etRows.getText().toString(), 2), 1, 10);
                    int c = clamp(parseNum(etCols.getText().toString(), 2), 1, 6);
                    insertTable(r, c);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void insertTable(int rows, int cols) {
        String[][] cells = new String[rows][cols];
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++) cells[i][j] = "";
        String html = TableSpan.buildHtml(cells);
        int pos = Math.max(contentInput.getSelectionStart(), 0);
        Editable ed = contentInput.getText();
        String prefix = (pos > 0 && ed.charAt(pos - 1) != '\n') ? "\n" : "";
        ed.insert(pos, prefix + "\n￼\n");
        int sp = pos + prefix.length() + 1;
        ed.setSpan(new TableSpan(html, rows, cols), sp, sp + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        scheduleSave();
    }

    private void openTableEditor(TableSpan ts) {
        final String[][][] holder = new String[][][]{TableSpan.parseCells(ts.html)};
        final java.util.List<java.util.List<EditText>>[] cellsRef =
                new java.util.List[]{new java.util.ArrayList<java.util.List<EditText>>()};

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad / 2, pad, pad / 2);

        LinearLayout gridBox = new LinearLayout(this);
        gridBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(gridBox);

        Runnable render = () -> {
            gridBox.removeAllViews();
            cellsRef[0].clear();
            String[][] d = holder[0];
            for (String[] row : d) {
                LinearLayout tr = new LinearLayout(this);
                tr.setOrientation(LinearLayout.HORIZONTAL);
                java.util.List<EditText> rowCells = new java.util.ArrayList<>();
                for (String cell : row) {
                    EditText et = new EditText(this);
                    et.setText(cell);
                    et.setTextSize(14);
                    et.setPadding(dp(6), dp(6), dp(6), dp(6));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                    lp.setMargins(2, 2, 2, 2);
                    tr.addView(et, lp);
                    rowCells.add(et);
                }
                gridBox.addView(tr);
                cellsRef[0].add(rowCells);
            }
        };
        render.run();

        Runnable readBack = () -> {
            java.util.List<java.util.List<EditText>> refs = cellsRef[0];
            String[][] d = new String[refs.size()][];
            for (int i = 0; i < refs.size(); i++) {
                d[i] = new String[refs.get(i).size()];
                for (int j = 0; j < refs.get(i).size(); j++) {
                    d[i][j] = refs.get(i).get(j).getText().toString();
                }
            }
            holder[0] = d;
        };

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button bRow = new Button(this);
        bRow.setAllCaps(false);
        bRow.setText(getString(R.string.table_add_row));
        bRow.setOnClickListener(v -> {
            readBack.run();
            String[][] d = holder[0];
            String[][] nd = new String[d.length + 1][d.length > 0 ? d[0].length : 1];
            for (int i = 0; i < d.length; i++)
                System.arraycopy(d[i], 0, nd[i], 0, d[i].length);
            for (int j = 0; j < nd[0].length; j++) nd[d.length][j] = "";
            holder[0] = nd;
            render.run();
        });
        Button bCol = new Button(this);
        bCol.setAllCaps(false);
        bCol.setText(getString(R.string.table_add_col));
        bCol.setOnClickListener(v -> {
            readBack.run();
            String[][] d = holder[0];
            String[][] nd = new String[d.length][d.length > 0 ? d[0].length + 1 : 1];
            for (int i = 0; i < d.length; i++) {
                System.arraycopy(d[i], 0, nd[i], 0, d[i].length);
                nd[i][d[i].length] = "";
            }
            holder[0] = nd;
            render.run();
        });
        btnRow.addView(bRow, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnRow.addView(bCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(btnRow);

        new AlertDialog.Builder(this)
                .setTitle(R.string.table_edit_title)
                .setView(root)
                .setPositiveButton(R.string.save, (d, w) -> {
                    readBack.run();
                    String[][] vals = holder[0];
                    ts.html = TableSpan.buildHtml(vals);
                    ts.rows = vals.length;
                    ts.cols = vals.length > 0 ? vals[0].length : 1;
                    ts.refreshLabel();
                    contentInput.invalidate();
                    scheduleSave();
                })
                .setNeutralButton(R.string.table_delete, (d, w) -> {
                    Editable ed = contentInput.getText();
                    int s = ed.getSpanStart(ts), e2 = ed.getSpanEnd(ts);
                    if (s >= 0 && e2 > s) ed.delete(s, e2);
                    scheduleSave();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static int parseNum(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------- kunci catatan ----------

    private void toggleNoteLock() {
        withNoteId(id -> {
            if (note.locked) {
                askPinThen(() -> {
                    note.locked = false;
                    saveNow();
                    Toast.makeText(this, R.string.note_unlocked, Toast.LENGTH_SHORT).show();
                });
            } else {
                if (!VaultPinActivity.isPinSet(this)) {
                    Toast.makeText(this, R.string.lock_need_pin, Toast.LENGTH_LONG).show();
                    NoteLockGate.requestPinSetup(this, RC_PIN_SETUP);
                    return;
                }
                note.locked = true;
                saveNow();
                Toast.makeText(this, R.string.note_locked, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void askPinThen(Runnable ok) {
        new com.hozinking.notes.ui.dialog.IosDialog(this)
                .setTitle(getString(R.string.lock_pin_title))
                .setInput(getString(R.string.lock_pin_hint), pin -> {
                    if (VaultPinActivity.checkPin(this, pin)) {
                        ok.run();
                    } else {
                        Toast.makeText(this, R.string.lock_pin_wrong,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setInputNumeric(true)
                .setPositive(getString(R.string.lock_open), null)
                .setNegative(getString(R.string.cancel), null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    @androidx.annotation.Nullable android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_PIN_SETUP && resultCode == RESULT_OK
                && note != null && !note.locked) {
            note.locked = true;
            saveNow();
            Toast.makeText(this, R.string.note_locked, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- gambar ----------

    private void launchCamera() {
        try {
            File dir = new File(getCacheDir(), "camera");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "cap_" + System.currentTimeMillis() + ".jpg");
            //noinspection ResultOfMethodCallIgnored
            f.createNewFile();
            cameraUri = FileProvider.getUriForFile(
                    this, BuildConfig.APPLICATION_ID + ".fileprovider", f);
            cameraLauncher.launch(cameraUri);
        } catch (Exception e) {
            Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Foto Live: rekam video ~3 detik via kamera sistem, 1 frame tengah
     * jadi foto still. Disimpan terenkripsi sebagai pasangan still+video.
     */
    private void launchLivePhoto() {
        try {
            File dir = new File(getCacheDir(), "camera");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "live_" + System.currentTimeMillis() + ".mp4");
            //noinspection ResultOfMethodCallIgnored
            f.createNewFile();
            liveVideoUri = FileProvider.getUriForFile(
                    this, BuildConfig.APPLICATION_ID + ".fileprovider", f);
            Intent i = new Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE);
            i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, liveVideoUri);
            i.putExtra(android.provider.MediaStore.EXTRA_DURATION_LIMIT, 3);
            i.putExtra(android.provider.MediaStore.EXTRA_VIDEO_QUALITY, 0);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            liveVideoLauncher.launch(i);
        } catch (Exception e) {
            Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshImages() {
        if (noteId == -1 || note == null) {
            imageStrip.removeAllViews();
            imageHeader.setVisibility(View.GONE);
            return;
        }
        NoteRepository.get(this).getImages(noteId, list -> main.post(() -> {
            imageStrip.removeAllViews();
            if (list == null) {
                imageHeader.setVisibility(View.GONE);
                return;
            }
            note.imageCount = list.size();

            // Header hitungan: total lampiran + berapa foto live di note ini.
            int liveCount = 0;
            for (ImageRefEntity im : list) {
                if (LivePhotoUtils.isLivePhoto(im)) liveCount++;
            }
            if (list.isEmpty()) {
                imageHeader.setVisibility(View.GONE);
            } else {
                imageHeader.setVisibility(View.VISIBLE);
                String h = getString(R.string.attachments_header, list.size());
                if (liveCount > 0) {
                    h += "  •  " + getString(R.string.live_count, liveCount);
                }
                imageHeader.setText(h);
            }

            for (int idx = 0; idx < list.size(); idx++) {
                final int position = idx;
                final ImageRefEntity img = list.get(idx);
                View item = getLayoutInflater()
                        .inflate(R.layout.item_editor_image, imageStrip, false);
                ImageView iv = item.findViewById(R.id.img);
                View liveBadge = item.findViewById(R.id.live_badge);
                final boolean isLive = LivePhotoUtils.isLivePhoto(img);
                liveBadge.setVisibility(isLive ? View.VISIBLE : View.GONE);
                imageStrip.addView(item);
                bindStripImage(item, iv, img, isLive);
                // Tap: buka image viewer fullscreen (swipe + zoom).
                item.setOnClickListener(v -> openImageViewer(position));
                item.findViewById(R.id.btn_remove)
                        .setOnClickListener(x -> confirmRemoveImage(img));
            }
        }));
    }

    /**
     * Muat gambar untuk strip lampiran: pakai thumbnail tajam 512px dulu
     * (cepat & hemat memori); fallback ke gambar penuh untuk data lama
     * yang belum punya thumbnail. Foto live: ketuk -> muat gambar penuh
     * lalu buka viewer (viewer butuh still resolusi penuh).
     */
    private void bindStripImage(View item, ImageView iv,
                                ImageRefEntity img, boolean isLive) {
        NoteRepository.get(this).loadThumbPlain(img, tbytes -> {
            Bitmap tb = null;
            try {
                if (tbytes != null) {
                    tb = BitmapFactory.decodeByteArray(tbytes, 0, tbytes.length);
                }
            } catch (Exception ignored) {
            }
            if (tb != null) {
                Bitmap f = tb;
                main.post(() -> {
                    iv.setImageBitmap(f);
                    bindLiveClick(item, img, isLive);
                });
                return;
            }
            // Fallback: data lama tanpa thumbnail -> gambar penuh.
            NoteRepository.get(this).loadImagePlain(img, bytes -> {
                Bitmap bmp = null;
                try {
                    if (bytes != null) {
                        bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    }
                } catch (Exception ignored) {
                }
                Bitmap f = bmp;
                main.post(() -> {
                    if (f != null) iv.setImageBitmap(f);
                    bindLiveClick(item, img, isLive);
                });
            });
        });
    }

    private void bindLiveClick(View item, ImageRefEntity img, boolean isLive) {
        if (!isLive) return;
        // Tap: buka viewer yang langsung autoplay klip geraknya (loop, muted).
        item.setOnClickListener(v ->
                NoteRepository.get(this).loadImagePlain(img, bytes ->
                        main.post(() -> new com.hozinking.notes.ui.dialog
                                .LivePhotoViewerDialog(
                                EditorActivity.this, img, bytes)
                                .show())));
        // Tahan: preview inline — popup kecil muter klip (muted, loop).
        // Lepas: popup tutup, kembali ke still.
        item.setOnTouchListener((v, event) -> {
            int a = event.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                cancelLivePress();
                livePressRunnable = () -> showLivePreview(v, img);
                livePressHandler.postDelayed(livePressRunnable,
                        ViewConfiguration.getLongPressTimeout());
            } else if (a == MotionEvent.ACTION_UP
                    || a == MotionEvent.ACTION_CANCEL) {
                cancelLivePress();
                dismissLivePreview();
            }
            return false; // teruskan event agar tap tetap jalan
        });
    }

    private void cancelLivePress() {
        livePressHandler.removeCallbacksAndMessages(null);
        livePressRunnable = null;
    }

    /**
     * Preview inline Foto Live: popup kecil di tengah layar yang langsung
     * memutar klip (muted + loop). Dipanggil setelah thumbnail ditahan
     * selama long-press timeout.
     */
    private void showLivePreview(View anchor, ImageRefEntity img) {
        dismissLivePreview();
        Context ctx = anchor.getContext();
        float d = ctx.getResources().getDisplayMetrics().density;
        int size = (int) (280 * d);

        FrameLayout frame = new FrameLayout(ctx);
        frame.setBackgroundResource(R.drawable.bg_dialog_card);
        int pad = (int) (10 * d);
        frame.setPadding(pad, pad, pad, pad);

        livePreviewVideo = new VideoView(ctx);
        livePreviewVideo.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(livePreviewVideo);

        TextView badge = new TextView(ctx);
        badge.setText("LIVE");
        badge.setTextColor(0xFFFFFFFF);
        badge.setTextSize(10);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setBackgroundResource(R.drawable.badge_live);
        int bp = (int) (6 * d);
        int bpt = (int) (2 * d);
        badge.setPadding(bp, bpt, bp, bpt);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        int m = (int) (8 * d);
        blp.setMargins(m, m, m, m);
        badge.setLayoutParams(blp);
        frame.addView(badge);

        livePreviewPopup = new PopupWindow(frame, size, size, false);
        livePreviewPopup.showAtLocation(anchor, Gravity.CENTER, 0, 0);

        livePreviewVideo.setOnPreparedListener(mp -> {
            try {
                mp.setLooping(true);
                mp.setVolume(0f, 0f);
            } catch (Exception ignored) {
            }
            if (livePreviewVideo != null) livePreviewVideo.start();
        });
        livePreviewVideo.setOnErrorListener((mp, what, extra) -> {
            dismissLivePreview();
            return true;
        });

        NoteRepository.get(ctx).loadLiveVideoPlain(img, bytes ->
                main.post(() -> {
                    if (livePreviewPopup == null || bytes == null) {
                        dismissLivePreview();
                        return;
                    }
                    try {
                        File dir = new File(getCacheDir(), "liveprev");
                        //noinspection ResultOfMethodCallIgnored
                        dir.mkdirs();
                        livePreviewTemp = new File(dir,
                                "prev_" + img.id + ".mp4");
                        java.io.FileOutputStream out =
                                new java.io.FileOutputStream(livePreviewTemp);
                        out.write(bytes);
                        out.close();
                        if (livePreviewVideo != null) {
                            livePreviewVideo.setVideoPath(
                                    livePreviewTemp.getAbsolutePath());
                        }
                    } catch (Exception e) {
                        dismissLivePreview();
                    }
                }));
    }

    private void dismissLivePreview() {
        try {
            if (livePreviewVideo != null) livePreviewVideo.stopPlayback();
        } catch (Exception ignored) {
        }
        livePreviewVideo = null;
        if (livePreviewPopup != null) {
            try {
                livePreviewPopup.dismiss();
            } catch (Exception ignored) {
            }
            livePreviewPopup = null;
        }
        if (livePreviewTemp != null) {
            //noinspection ResultOfMethodCallIgnored
            livePreviewTemp.delete();
            livePreviewTemp = null;
        }
    }

    /** Buka image viewer fullscreen pada posisi yang diketuk. */
    private void openImageViewer(int position) {
        if (noteId == -1) return;
        Intent i = new Intent(this, ImageViewerActivity.class);
        i.putExtra(ImageViewerActivity.EXTRA_NOTE_ID, noteId);
        i.putExtra(ImageViewerActivity.EXTRA_POSITION, position);
        viewerLauncher.launch(i);
    }

    private void confirmRemoveImage(ImageRefEntity img) {
        new IosDialog(this)
                .setTitle(getString(R.string.notes_remove_image))
                .setPositive(getString(R.string.delete), () ->
                        NoteRepository.get(this)
                                .deleteImage(img, v -> main.post(this::refreshImages)))
                .setNegative(getString(R.string.cancel), () -> {
                })
                .show();
    }

    // ---------- Rekaman suara (audio notes) ----------

    /** Tombol toolbar: mulai / hentikan perekaman. */
    private void onAudioButton() {
        if (recorder != null) {
            stopRecording();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startRecording();
        } else {
            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    /** Mulai merekam (AAC .m4a ke file temp di cache). */
    private void startRecording() {
        withNoteId(id -> {
            try {
                stopPlayback();
                File tmp = new File(getCacheDir(),
                        "rec_" + System.currentTimeMillis() + ".m4a");
                MediaRecorder mr = new MediaRecorder();
                mr.setAudioSource(MediaRecorder.AudioSource.MIC);
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mr.setOutputFile(tmp.getAbsolutePath());
                mr.prepare();
                mr.start();
                recorder = mr;
                recTempFile = tmp;
                recStartMs = System.currentTimeMillis();
                recNoteId = id;
                audioBtnLabel.setText(R.string.audio_recording);
            } catch (Exception e) {
                cleanupRecorder();
                Toast.makeText(this, R.string.audio_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Hentikan perekaman; simpan bila durasi >= 1 detik. */
    private void stopRecording() {
        MediaRecorder mr = recorder;
        cleanupRecorder();
        audioBtnLabel.setText(R.string.audio_record);
        if (mr == null) return;
        long duration = System.currentTimeMillis() - recStartMs;
        try {
            mr.stop();
        } catch (Exception ignored) {
        }
        try {
            mr.release();
        } catch (Exception ignored) {
        }
        File tmp = recTempFile;
        recTempFile = null;
        if (duration < 1000 || tmp == null) {
            if (tmp != null) tmp.delete();
            Toast.makeText(this, R.string.audio_too_short, Toast.LENGTH_SHORT).show();
            return;
        }
        long nid = recNoteId;
        NoteRepository.get(this).saveAudioRecording(nid, tmp, duration, ref ->
                main.post(() -> {
                    if (ref != null) {
                        refreshAudio();
                        Toast.makeText(this, R.string.audio_saved,
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, R.string.audio_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                }));
    }

    private void cleanupRecorder() {
        recorder = null;
    }

    /** Muat ulang daftar rekaman suara milik catatan ini. */
    private void refreshAudio() {
        if (audioList == null) return;
        if (noteId == -1 || note == null) {
            audioList.removeAllViews();
            audioHeader.setVisibility(View.GONE);
            return;
        }
        NoteRepository.get(this).getAudioRefs(noteId, list -> main.post(() -> {
            stopPlayback();
            audioList.removeAllViews();
            if (list == null || list.isEmpty()) {
                audioHeader.setVisibility(View.GONE);
                return;
            }
            audioHeader.setVisibility(View.VISIBLE);
            audioHeader.setText(
                    getString(R.string.audio_recordings_header, list.size()));
            for (AudioRefEntity a : list) {
                View item = getLayoutInflater()
                        .inflate(R.layout.item_editor_audio, audioList, false);
                ImageButton btnPlay = item.findViewById(R.id.btn_play);
                TextView tvDur = item.findViewById(R.id.tv_duration);
                tvDur.setText(formatDuration(a.durationMs));
                btnPlay.setOnClickListener(v -> togglePlay(a, btnPlay));
                item.findViewById(R.id.btn_remove)
                        .setOnClickListener(x -> confirmRemoveAudio(a));
                audioList.addView(item);
            }
        }));
    }

    /** Putar / jeda satu rekaman. Dekripsi ke file temp, putar via MediaPlayer. */
    private void togglePlay(AudioRefEntity a, ImageButton btn) {
        if (playingRef != null && playingRef.id == a.id) {
            stopPlayback();
            return;
        }
        stopPlayback();
        NoteRepository.get(this).loadAudioPlain(a, bytes -> main.post(() -> {
            if (bytes == null || bytes.length == 0) {
                Toast.makeText(this, R.string.audio_play_failed,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                cleanupPlayTemp();
                playTempFile = new File(getCacheDir(),
                        "play_" + System.currentTimeMillis() + ".m4a");
                try (FileOutputStream fos = new FileOutputStream(playTempFile)) {
                    fos.write(bytes);
                }
                MediaPlayer mp = new MediaPlayer();
                mp.setDataSource(playTempFile.getAbsolutePath());
                mp.prepare();
                final AudioRefEntity refF = a;
                final ImageButton btnF = btn;
                mp.setOnCompletionListener(m -> stopPlayback());
                mp.setOnErrorListener((m, what, extra) -> {
                    stopPlayback();
                    Toast.makeText(this, R.string.audio_play_failed,
                            Toast.LENGTH_SHORT).show();
                    return true;
                });
                mp.start();
                player = mp;
                playingRef = refF;
                playingBtn = btnF;
                btnF.setImageResource(R.drawable.ic_pause);
            } catch (Exception e) {
                stopPlayback();
                Toast.makeText(this, R.string.audio_play_failed,
                        Toast.LENGTH_SHORT).show();
            }
        }));
    }

    /** Hentikan pemutaran + bersihkan file temp. Aman dipanggil kapan saja. */
    private void stopPlayback() {
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
            } catch (Exception ignored) {
            }
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        if (playingBtn != null) {
            try {
                playingBtn.setImageResource(R.drawable.ic_play);
            } catch (Exception ignored) {
            }
            playingBtn = null;
        }
        playingRef = null;
        cleanupPlayTemp();
    }

    private void cleanupPlayTemp() {
        if (playTempFile != null) {
            playTempFile.delete();
            playTempFile = null;
        }
    }

    /** Konfirmasi + hapus satu rekaman. */
    private void confirmRemoveAudio(AudioRefEntity a) {
        stopPlayback();
        new IosDialog(this)
                .setTitle(getString(R.string.audio_delete_title))
                .setMessage(getString(R.string.audio_delete_msg))
                .setPositive(getString(R.string.delete), () ->
                        NoteRepository.get(this).deleteAudio(a, ok -> main.post(() -> {
                            if (Boolean.TRUE.equals(ok)) {
                                refreshAudio();
                                Toast.makeText(this, R.string.audio_deleted,
                                        Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(this, R.string.audio_delete_failed,
                                        Toast.LENGTH_SHORT).show();
                            }
                        })))
                .setNegative(getString(R.string.cancel), () -> {
                })
                .show();
    }

    private static String formatDuration(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60);
    }

    // ---------- menu ⋯ ----------

    private String displayTitle() {
        if (note == null || note.title == null || note.title.trim().isEmpty()) {
            return getString(R.string.notes_no_title);
        }
        return note.title.trim();
    }

    private void showMenu() {
        if (note == null) return;
        String reminderLabel = hasActiveReminder()
                ? getString(R.string.reminder_cancel)
                : getString(R.string.reminder_set);
        new IosActionSheet(this)
                .setTitle(displayTitle())
                .addItem(getString(R.string.notes_color_title), this::showColorSheet)
                .addItem(getString(R.string.notes_folder_title), this::showFolderSheet)
                .addItem(getString(R.string.notes_tags_title), this::showTagsDialog)
                .addItem(getString(R.string.table_insert), this::showInsertTableDialog)
                .addItem(getString(R.string.editor_find_in_note), this::showFindBar)
                .addItem(getString(R.string.editor_insert_datetime), this::insertDateTime)
                .addItem(getString(R.string.editor_insert_link), this::showInsertNoteLink)
                .addItem(getString(R.string.editor_insert_separator), this::insertSeparator)
                .addItem(getString(speaker != null && speaker.isSpeaking()
                                ? R.string.editor_stop_reading : R.string.editor_read_aloud),
                        this::toggleReadAloud)
                .addItem(getString(R.string.editor_copy_note), this::copyNote)
                .addItem(getString(R.string.share), this::shareNote)
                .addItem(getString(R.string.share_as_image), this::shareAsImage)
                .addItem(getString(R.string.export_pdf), this::exportPdf)
                .addItem(getString(R.string.export_md), this::exportMarkdown)
                .addItem(getString(R.string.export_html), this::exportHtml)
                .addItem(getString(R.string.duplicate), this::duplicateNote)
                .addItem(getString(R.string.revision_history), this::showRevisionHistory)
                .addItem(getString(R.string.checklist_sort_checked), this::sortCheckedToBottom)
                .addItem(getString(R.string.checklist_clear_checked), this::clearCheckedItems)
                .addItem(getString(note.locked ? R.string.note_unlock : R.string.note_lock),
                        this::toggleNoteLock)
                .addItem(reminderLabel, this::showReminderDialog)
                .addItem(getString(note.pinned ? R.string.unpin : R.string.pin), () -> {
                    note.pinned = !note.pinned;
                    saveNow();
                })
                .addItem(getString(note.archived ? R.string.unarchive : R.string.archive), () -> {
                    note.archived = !note.archived;
                    saveNow();
                    finish();
                })
                .addDestructive(getString(R.string.delete), this::confirmDelete)
                .show();
    }

    // ---------- v1.17: bacakan catatan (TTS) ----------

    /** Toggle bacakan judul + isi catatan memakai TextToSpeech sistem. */
    private void toggleReadAloud() {
        if (speaker == null) speaker = new NoteSpeaker(this);
        speaker.toggle(readableText());
    }

    /** Teks yang enak dibacakan: judul + isi tanpa penanda checklist/markdown. */
    private String readableText() {
        String raw = contentInput.getText().toString();
        String plain;
        if (note != null && note.contentHtml) {
            try {
                plain = android.text.Html.fromHtml(raw,
                        android.text.Html.FROM_HTML_MODE_LEGACY).toString();
            } catch (Exception e) {
                plain = raw;
            }
        } else {
            plain = raw;
        }
        // Buang penanda checklist "- [ ] "/"- [x] " agar TTS tidak mengejanya.
        plain = plain.replaceAll("(?m)^\\s*- \\[[ xX]\\]\\s?", "");
        String text = (displayTitle() + ". " + plain).trim();
        return text;
    }

    // ---------- sketsa ----------

    /** Buka kanvas sketsa; pastikan note sudah tersimpan dulu. */
    private void openSketch() {
        if (previewMode) togglePreview();
        withNoteId(id -> {
            Intent i = new Intent(this, SketchActivity.class);
            i.putExtra("noteId", id);
            sketchLauncher.launch(i);
        });
    }

    // ---------- pratinjau ----------

    /**
     * Toggle mode pratinjau: tampilkan isi catatan sebagai bacaan
     * read-only (judul tebal + isi ter-render, link bisa di-tap).
     */
    private void togglePreview() {
        previewMode = !previewMode;
        if (previewMode) {
            saveNow(); // flush perubahan yang masih tertunda
            clearFindHighlights();
            renderPreview();
            titleInput.setVisibility(View.GONE);
            titleDivider.setVisibility(View.GONE);
            contentInput.setVisibility(View.GONE);
            previewContent.setVisibility(View.VISIBLE);
            previewLabel.setText(R.string.preview_edit);
            editorScroll.scrollTo(0, 0);
            // v1.22: tampilkan kontrol kecerahan mode baca.
            if (previewBrightnessRow != null) {
                previewBrightnessRow.setVisibility(View.VISIBLE);
                int saved = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
                        .getInt(KEY_PREVIEW_BRIGHTNESS, BRIGHTNESS_SYSTEM);
                if (saved >= 0 && previewBrightness != null) {
                    previewBrightness.setProgress(saved);
                    applyPreviewBrightness(saved);
                    if (previewBrightnessPct != null) {
                        previewBrightnessPct.setText(
                                getString(R.string.preview_brightness_pct, saved));
                    }
                } else if (previewBrightnessPct != null) {
                    previewBrightnessPct.setText(
                            getString(R.string.preview_brightness_system));
                }
            }
        } else {
            previewContent.setVisibility(View.GONE);
            titleInput.setVisibility(View.VISIBLE);
            titleDivider.setVisibility(View.VISIBLE);
            contentInput.setVisibility(View.VISIBLE);
            previewLabel.setText(R.string.preview_title);
            // v1.22: keluar mode baca → kembalikan kecerahan sistem.
            if (previewBrightnessRow != null) previewBrightnessRow.setVisibility(View.GONE);
            applyPreviewBrightness(BRIGHTNESS_SYSTEM);
        }
    }

    /**
     * v1.26: bangun isi pratinjau (dipanggil saat masuk mode pratinjau DAN
     * setiap kali kotak checklist di pratinjau diketuk, agar tampilan tetap
     * di mode baca). Judul tebal + isi; baris checklist dirender sebagai
     * "☐ "/"☑ " dengan kotak yang BISA DIKETUK (konsep checklist inline
     * ala Elite Memo Pro, MIT — ide dipinjam, implementasi sendiri), dan
     * item yang sudah dicentang tampil tercoret. Link tetap bisa di-tap
     * lewat LinkMovementMethod (TextView read-only, jadi tidak ada masalah
     * kursor seperti di EditText).
     */
    private void renderPreview() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String t = titleInput.getText().toString().trim();
        if (!t.isEmpty()) {
            int s = sb.length();
            sb.append(t);
            sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new RelativeSizeSpan(1.25f), s, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append("\n\n");
        }
        int contentStart = sb.length();
        String source = contentInput.getText().toString();
        sb.append(contentInput.getText());
        decoratePreviewChecklist(sb, source, contentStart);
        decoratePreviewBacklinks(sb, source, contentStart);
        previewContent.setText(sb);
        previewContent.setMovementMethod(LinkMovementMethod.getInstance());
    }

    /**
     * v1.26: ganti penanda "- [ ] "/"- [x] " di salinan pratinjau dengan
     * kotak ketuk + coret untuk item selesai. Offset sumber (di
     * contentInput) dicatat per baris SEBELUM penggantian, jadi ketukan
     * selalu mengubah baris yang benar meski panjang baris di pratinjau
     * menyusut 4 karakter per item.
     */
    private void decoratePreviewChecklist(SpannableStringBuilder sb,
            String source, int contentStart) {
        int srcPos = 0;
        int sbPos = contentStart;
        while (srcPos <= source.length()) {
            int srcNl = source.indexOf('\n', srcPos);
            int srcEnd = srcNl < 0 ? source.length() : srcNl;
            String line = source.substring(srcPos, srcEnd);
            int indent = firstNonSpace(line);
            String marker = indent < line.length() ? line.substring(indent) : "";
            boolean isOpen = marker.startsWith("- [ ]");
            boolean isDone = marker.startsWith("- [x]") || marker.startsWith("- [X]");
            if (isOpen || isDone) {
                int replacedLen = Math.min(6, line.length() - indent);
                sb.replace(sbPos + indent, sbPos + indent + replacedLen,
                        isDone ? "☑ " : "☐ ");
                sb.setSpan(new PreviewCheckSpan(srcPos, srcEnd),
                        sbPos + indent, sbPos + indent + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (isDone) {
                    int itemEnd = sbPos + line.length() - (replacedLen - 2);
                    if (itemEnd > sbPos + indent + 2) {
                        sb.setSpan(new StrikethroughSpan(),
                                sbPos + indent + 2, itemEnd,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
                sbPos += line.length() - (replacedLen - 2);
            } else {
                sbPos += line.length();
            }
            if (srcNl < 0) break;
            srcPos = srcNl + 1;
            sbPos += 1; // karakter '\n' yang sama di pratinjau
        }
    }

    /** Span kotak checklist di pratinjau: ketuk untuk mencentang/membuka. */
    private class PreviewCheckSpan extends ClickableSpan {
        private final int srcStart;
        private final int srcEnd;

        PreviewCheckSpan(int srcStart, int srcEnd) {
            this.srcStart = srcStart;
            this.srcEnd = srcEnd;
        }

        @Override
        public void onClick(View widget) {
            Editable ed = contentInput.getText();
            if (srcStart < 0 || srcStart >= ed.length()) return;
            int end = Math.min(srcEnd, ed.length());
            String line = ed.subSequence(srcStart, end).toString();
            int ind = firstNonSpace(line);
            String m = ind < line.length() ? line.substring(ind) : "";
            boolean isOpen = m.startsWith("- [ ]");
            boolean isDone = m.startsWith("- [x]") || m.startsWith("- [X]");
            if (!isOpen && !isDone) return;
            int markerLen = Math.min(6, line.length() - ind);
            String rest = line.length() > ind + markerLen
                    ? line.substring(ind + markerLen) : "";
            String next = line.substring(0, ind)
                    + (isOpen ? "- [x] " : "- [ ] ") + rest;
            ed.replace(srcStart, end, next);
            scheduleSave();
            renderPreview(); // tetap di mode pratinjau, tampilan diperbarui
        }

        @Override
        public void updateDrawState(android.text.TextPaint ds) {
            super.updateDrawState(ds);
            ds.setUnderlineText(false); // kotak bukan tautan: tanpa garis bawah
        }
    }

    /**
     * v1.27: tautan internal [[Judul Catatan]] di pratinjau (konsep backlink
     * ala TidGi-Mobile, ide dipinjam, implementasi sendiri). Berjalan kaki
     * per baris seperti v1.26 dan memakai pemetaan posisi yang sama, karena
     * baris checklist di pratinjau memendek: penanda 6 karakter diganti
     * kotak 2 karakter. Tautan di judul tidak didukung (judul dirender
     * terpisah, bukan dari isi).
     */
    private void decoratePreviewBacklinks(SpannableStringBuilder sb,
            String source, int contentStart) {
        int srcPos = 0;
        int sbPos = contentStart;
        while (srcPos <= source.length()) {
            int srcNl = source.indexOf('\n', srcPos);
            int srcEnd = srcNl < 0 ? source.length() : srcNl;
            String line = source.substring(srcPos, srcEnd);
            int indent = firstNonSpace(line);
            String marker = indent < line.length() ? line.substring(indent) : "";
            boolean isCheck = marker.startsWith("- [ ]")
                    || marker.startsWith("- [x]") || marker.startsWith("- [X]");
            int replacedLen = isCheck ? Math.min(6, line.length() - indent) : 0;
            int shift = isCheck ? replacedLen - 2 : 0;
            int from = 0;
            while (true) {
                int open = line.indexOf("[[", from);
                if (open < 0) break;
                int close = line.indexOf("]]", open + 2);
                if (close < 0) break;
                String title = line.substring(open + 2, close).trim();
                if (!title.isEmpty()) {
                    int sbStart = sbPos + mapPreviewPos(open, indent, replacedLen);
                    int sbEnd = sbPos + mapPreviewPos(close + 2, indent, replacedLen);
                    if (sbStart >= contentStart && sbEnd > sbStart
                            && sbEnd <= sb.length()) {
                        sb.setSpan(new PreviewLinkSpan(title), sbStart, sbEnd,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
                from = close + 2;
            }
            sbPos += line.length() - shift;
            if (srcNl < 0) break;
            srcPos = srcNl + 1;
            sbPos += 1; // karakter '\n' yang sama di pratinjau
        }
    }

    /** Petakan posisi dalam satu baris sumber ke posisi pratinjau (v1.26). */
    private static int mapPreviewPos(int p, int indent, int replacedLen) {
        if (replacedLen == 0) return p;
        if (p <= indent) return p;
        if (p < indent + replacedLen) return indent; // di dalam penanda
        return p - (replacedLen - 2);
    }

    /** Span tautan internal di pratinjau: ketuk untuk membuka catatan tujuan. */
    private class PreviewLinkSpan extends ClickableSpan {
        private final String title;

        PreviewLinkSpan(String title) {
            this.title = title;
        }

        @Override
        public void onClick(View widget) {
            NoteRepository.get(EditorActivity.this).findByExactTitle(title, noteId,
                    n -> main.post(() -> {
                        if (n == null) {
                            Toast.makeText(EditorActivity.this,
                                    getString(R.string.editor_link_not_found, title),
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Intent i = new Intent(EditorActivity.this,
                                EditorActivity.class);
                        i.putExtra("noteId", n.id);
                        startActivity(i);
                    }));
        }

        @Override
        public void updateDrawState(android.text.TextPaint ds) {
            super.updateDrawState(ds);
            ds.setUnderlineText(true); // tautan: bergaris bawah seperti biasa
        }
    }

    /** v1.27: sisipkan "[[Judul]]" di posisi kursor dari menu editor. */
    private void showInsertNoteLink() {
        if (previewMode) togglePreview();
        if (note == null) return;
        final EditText input = new EditText(this);
        input.setHint(R.string.editor_insert_link_hint);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle(R.string.editor_insert_link_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String title = input.getText().toString().trim();
                    if (title.isEmpty()) return;
                    int pos = Math.max(contentInput.getSelectionStart(), 0);
                    contentInput.getText().insert(pos, "[[" + title + "]]");
                    scheduleSave();
                    updateWordCount();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * v1.22: atur kecerahan hanya untuk jendela activity ini.
     * Nilai -1 = ikuti kecerahan sistem (tidak diubah).
     */
    private void applyPreviewBrightness(int pct) {
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = pct < 0 ? BRIGHTNESS_SYSTEM : Math.max(1, pct) / 100f;
        getWindow().setAttributes(lp);
    }

    // ---------- cari dalam catatan ----------

    private void showFindBar() {
        if (findBar.getVisibility() == View.VISIBLE) return;
        findBar.setVisibility(View.VISIBLE);
        findInput.setText("");
        findInput.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(findInput,
                    android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideFindBar() {
        findBar.setVisibility(View.GONE);
        if (replaceBar != null) replaceBar.setVisibility(View.GONE);
        findInput.setText("");
        clearFindHighlights();
    }

    /** Dibuka dari SearchActivity: isi bar cari dengan kata kunci & tampilkan. */
    private void autoOpenFindFromSearch() {
        String q = getIntent().getStringExtra("findQuery");
        if (q == null || q.trim().isEmpty()) return;
        showFindBar();
        findInput.setText(q.trim());
        findInput.setSelection(findInput.getText().length());
    }

    private void clearFindHighlights() {
        Editable e = contentInput.getText();
        for (FindSpan sp : e.getSpans(0, e.length(), FindSpan.class)) {
            e.removeSpan(sp);
        }
        findMatches.clear();
        findIndex = -1;
    }

    private void runFind() {
        clearFindHighlights();
        String q = findInput.getText().toString();
        if (q.isEmpty()) {
            findCount.setText("");
            return;
        }
        String lower = contentInput.getText().toString()
                .toLowerCase(java.util.Locale.ROOT);
        String ql = q.toLowerCase(java.util.Locale.ROOT);
        int from = 0;
        while (findMatches.size() < 500) {
            int idx = lower.indexOf(ql, from);
            if (idx < 0) break;
            findMatches.add(new int[]{idx, idx + q.length()});
            from = idx + q.length();
        }
        if (findMatches.isEmpty()) {
            findCount.setText("0");
            return;
        }
        findIndex = 0;
        paintFindMatches();
    }

    private void paintFindMatches() {
        Editable e = contentInput.getText();
        for (FindSpan sp : e.getSpans(0, e.length(), FindSpan.class)) {
            e.removeSpan(sp);
        }
        for (int i = 0; i < findMatches.size(); i++) {
            int[] m = findMatches.get(i);
            int color = (i == findIndex) ? FIND_CURRENT_COLOR : FIND_MATCH_COLOR;
            e.setSpan(new FindSpan(color), m[0], m[1],
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        findCount.setText(getString(R.string.editor_find_count,
                findIndex + 1, findMatches.size()));
        scrollToFindMatch(findIndex);
    }

    private void findStep(int dir) {
        if (findMatches.isEmpty()) return;
        findIndex = (findIndex + dir + findMatches.size()) % findMatches.size();
        paintFindMatches();
    }

    private void scrollToFindMatch(int i) {
        if (i < 0 || i >= findMatches.size()) return;
        int[] m = findMatches.get(i);
        contentInput.requestFocus();
        try {
            contentInput.setSelection(m[0], m[1]);
        } catch (Exception ignored) {
        }
        if (editorScroll != null && contentInput.getLayout() != null) {
            int line = contentInput.getLayout().getLineForOffset(m[0]);
            int y = contentInput.getTop()
                    + contentInput.getLayout().getLineTop(line);
            editorScroll.smoothScrollTo(0, Math.max(0, y - 200));
        }
    }

    // ---------- v1.23: cari & ganti ----------

    private void toggleReplaceBar() {
        if (replaceBar == null) return;
        boolean show = replaceBar.getVisibility() != View.VISIBLE;
        replaceBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) replaceInput.requestFocus();
    }

    /** Ganti kemunculan yang sedang disorot, lalu pindah ke hasil berikutnya. */
    private void replaceCurrent() {
        if (findMatches.isEmpty() || findIndex < 0 || findIndex >= findMatches.size()
                || replaceInput == null) {
            return;
        }
        String repl = replaceInput.getText().toString();
        Editable e = contentInput.getText();
        int[] m = findMatches.get(findIndex);
        int start = m[0];
        e.replace(m[0], m[1], repl);
        scheduleSave();
        runFind();
        // Pindah ke hasil pertama pada/setelah posisi penggantian.
        int next = start + repl.length();
        for (int i = 0; i < findMatches.size(); i++) {
            if (findMatches.get(i)[0] >= next) {
                findIndex = i;
                paintFindMatches();
                return;
            }
        }
    }

    /** Ganti semua kemunculan (dari belakang agar indeks tetap valid). */
    private void replaceAll() {
        if (findMatches.isEmpty() || replaceInput == null) return;
        String repl = replaceInput.getText().toString();
        Editable e = contentInput.getText();
        int count = 0;
        for (int i = findMatches.size() - 1; i >= 0; i--) {
            int[] m = findMatches.get(i);
            e.replace(m[0], m[1], repl);
            count++;
        }
        scheduleSave();
        runFind();
        Toast.makeText(this, getString(R.string.editor_replaced_count, count),
                Toast.LENGTH_SHORT).show();
    }

    // ---------- v1.23: riwayat revisi ----------

    private static final java.text.SimpleDateFormat REVISION_FMT =
            new java.text.SimpleDateFormat("d MMM yyyy, HH:mm", new Locale("id", "ID"));

    /** Tampilkan daftar revisi (terbaru dulu) sebagai action sheet. */
    private void showRevisionHistory() {
        if (note == null) return;
        withNoteId(id -> NoteRepository.get(this).listRevisions(id,
                list -> main.post(() -> showRevisionList(list))));
    }

    private void showRevisionList(List<NoteRevision> list) {
        if (list == null || list.isEmpty()) {
            Toast.makeText(this, R.string.revision_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        IosActionSheet sheet = new IosActionSheet(this)
                .setTitle(getString(R.string.revision_history));
        int shown = Math.min(list.size(), REVISION_LIST_SHOWN);
        for (int i = 0; i < shown; i++) {
            final NoteRevision r = list.get(i);
            String label = REVISION_FMT.format(new java.util.Date(r.savedAt))
                    + " — " + previewOf(r);
            sheet.addItem(label, () -> showRevisionDetail(r));
        }
        sheet.show();
    }

    /** Jumlah entri riwayat yang ditampilkan di daftar (DAO menyimpan 20). */
    private static final int REVISION_LIST_SHOWN = 20;

    /** Cuplikan isi revisi untuk daftar (maks ~60 karakter, tanpa tag HTML). */
    private static String previewOf(NoteRevision r) {
        String t = r.title == null ? "" : r.title.trim();
        if (!t.isEmpty()) return t.length() > 60 ? t.substring(0, 60) + "…" : t;
        String c = r.content == null ? "" : r.content;
        if (r.contentHtml) c = c.replaceAll("<[^>]*>", " ");
        c = c.replaceAll("\\s+", " ").trim();
        if (c.isEmpty()) return "—";
        return c.length() > 60 ? c.substring(0, 60) + "…" : c;
    }

    /** Pratinjau satu revisi + tombol pulihkan. */
    private void showRevisionDetail(NoteRevision r) {
        String body = r.content == null ? "" : r.content;
        if (r.contentHtml) body = body.replaceAll("<[^>]*>", "");
        String title = r.title == null ? "" : r.title.trim();
        String msg = REVISION_FMT.format(new java.util.Date(r.savedAt))
                + (title.isEmpty() ? "" : "\n\n" + title)
                + "\n\n" + body.trim();
        if (msg.length() > 2000) msg = msg.substring(0, 2000) + "…";
        new IosDialog(this)
                .setTitle(getString(R.string.revision_history))
                .setMessage(msg)
                .setPositive(getString(R.string.revision_restore),
                        () -> restoreRevision(r))
                .setNegative(getString(R.string.cancel), null)
                .show();
    }

    /**
     * Pulihkan judul+isi dari revisi. Hasil pulihan otomatis jadi revisi baru
     * lewat saveNow(), jadi pemulihan bisa diurungkan via riwayat.
     */
    private void restoreRevision(NoteRevision r) {
        if (note == null) return;
        saveHandler.removeCallbacks(saveRunnable);
        note.title = r.title == null ? "" : r.title;
        note.content = r.content == null ? "" : r.content;
        note.contentHtml = r.contentHtml;
        titleInput.setText(note.title);
        setContentFromNote(note);
        undoRedo.clear();
        updateUndoRedoButtons();
        saveNow();
        Toast.makeText(this, R.string.revision_restored, Toast.LENGTH_SHORT).show();
    }

    // ---------- penghitung kata ----------

    private void updateWordCount() {
        if (wordCountView == null) return;
        String t = contentInput.getText().toString().trim();
        int words = t.isEmpty() ? 0 : t.split("\\s+").length;
        // v1.27: estimasi waktu baca (±200 kata/menit, minimal 1 menit).
        int minutes = words == 0 ? 0 : Math.max(1, (words + 199) / 200);
        wordCountView.setText(getString(R.string.editor_word_count, words, minutes));
    }

    // ---------- terima share dari aplikasi lain ----------

    /**
     * ACTION_SEND (teks/gambar) dari aplikasi lain dijadikan catatan baru.
     * Ide "share into" ala Markor; implementasi sendiri.
     */
    private void handleSharedContent(Intent intent) {
        if (intent == null || note == null) return;
        if (!Intent.ACTION_SEND.equals(intent.getAction())) return;
        if (noteId != -1) return; // hanya untuk catatan baru
        String type = intent.getType();
        if (type == null) return;
        if (type.startsWith("text/")) {
            String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (!TextUtils.isEmpty(subject)) titleInput.setText(subject);
            if (!TextUtils.isEmpty(text)) {
                contentInput.setText(text);
                try {
                    contentInput.setSelection(text.length());
                } catch (Exception ignored) {
                }
            }
            if (!TextUtils.isEmpty(subject) || !TextUtils.isEmpty(text)) {
                Toast.makeText(this, R.string.share_received,
                        Toast.LENGTH_SHORT).show();
            }
        } else if (type.startsWith("image/")) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) {
                withNoteId(id -> NoteRepository.get(this)
                        .addImageFromUri(this, id, uri,
                                img -> main.post(() -> {
                                    refreshImages();
                                    Toast.makeText(this,
                                            R.string.share_received,
                                            Toast.LENGTH_SHORT).show();
                                })));
            }
        }
    }

    // ---------- Bagikan & duplikat ----------

    /**
     * v1.28: salin judul + isi catatan (teks polos, penanda checklist
     * dibuang seperti di bacakan/TTS) ke clipboard, tanpa membuka
     * chooser bagikan. Sengaja TIDAK dihapus otomatis seperti salinan
     * sandi di PasswordVault: catatan memang dimaksudkan untuk ditempel
     * belakangan (mis. ke WhatsApp). Bekerja dari field editor, jadi
     * draf yang belum tersimpan pun bisa disalin.
     */
    private void copyNote() {
        String raw = contentInput.getText().toString();
        String plain;
        if (note != null && note.contentHtml) {
            try {
                plain = android.text.Html.fromHtml(raw,
                        android.text.Html.FROM_HTML_MODE_LEGACY).toString();
            } catch (Exception e) {
                plain = raw;
            }
        } else {
            plain = raw;
        }
        plain = plain.replaceAll("(?m)^\\s*- \\[[ xX]\\]\\s?", "").trim();
        String title = displayTitle();
        String text = plain.isEmpty() ? title : title + "\n\n" + plain;
        try {
            ClipboardManager cm = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText(title, text));
            Toast.makeText(this, R.string.editor_note_copied,
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            // Clipboard gagal: jangan tampilkan toast "disalin" yang salah.
        }
    }

    /** Bagikan judul + isi catatan sebagai teks polos ke aplikasi lain. */
    private void shareNote() {
        if (note == null) return;
        saveNow(); // sinkronkan field dulu (syncFieldsToNote jalan sinkron)
        String title = displayTitle();
        String body = note.content == null ? "" : note.content;
        if (note.contentHtml) {
            body = Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY).toString();
        }
        body = body.trim();
        String text = body.isEmpty() ? title : title + "\n\n" + body;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, title);
        i.putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(i, getString(R.string.share)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.share, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * v1.19: bagikan catatan sebagai gambar kartu (PNG).
     * Konsep "share note as an image" ala Shkiper; render via ImageShareExporter.
     */
    private void shareAsImage() {
        if (note == null) return;
        saveNow(); // sinkronkan field dulu
        String title = displayTitle();
        String body = note.content == null ? "" : note.content;
        if (note.contentHtml) {
            body = Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY).toString();
        }
        // Sederhanakan marker checklist markdown agar rapi di gambar.
        body = body.replaceAll("(?m)^\\s*- \\[ \\] ", "☐ ")
                .replaceAll("(?m)^\\s*- \\[[xX]\\] ", "☑ ");
        ImageShareExporter.exportAndShare(this, title, body.trim(), note.color);
    }

    /** Ekspor catatan ini menjadi PDF lalu bagikan. */
    private void exportPdf() {
        if (note == null) return;
        saveNow(); // sinkronkan field dulu
        String title = displayTitle();
        String body = note.content == null ? "" : note.content;
        if (note.contentHtml) {
            body = Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY).toString();
        }
        // Sederhanakan marker checklist markdown agar rapi di PDF.
        body = body.replaceAll("(?m)^\\s*- \\[ \\] ", "☐ ")
                .replaceAll("(?m)^\\s*- \\[[xX]\\] ", "☑ ");
        PdfExporter.exportAndShare(this, title, body.trim());
    }

    /** Ekspor catatan ini menjadi Markdown (.md) lalu bagikan. */
    private void exportMarkdown() {
        if (note == null) return;
        saveNow(); // sinkronkan field dulu
        MarkdownExporter.exportAndShare(this, displayTitle(),
                note.content == null ? "" : note.content, note.contentHtml);
    }

    /** Ekspor catatan ini menjadi HTML (.html) lalu bagikan. */
    private void exportHtml() {
        if (note == null) return;
        saveNow(); // sinkronkan field dulu
        HtmlExporter.exportAndShare(this, displayTitle(),
                note.content == null ? "" : note.content, note.contentHtml);
    }

    /** Duplikat catatan ini (teks + tag + warna + folder). */
    private void duplicateNote() {
        if (note == null) return;
        if (noteId == -1) {
            saveNow();
        }
        if (noteId == -1) {
            Toast.makeText(this, R.string.duplicate_need_save, Toast.LENGTH_SHORT).show();
            return;
        }
        final long srcId = noteId;
        NoteRepository.get(this).duplicateNote(srcId, id -> main.post(() -> {
            if (id != null && id > 0) {
                Toast.makeText(this, R.string.note_duplicated, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.duplicate_failed, Toast.LENGTH_SHORT).show();
            }
        }));
    }

    // ---------- Pengingat ----------

    private boolean hasActiveReminder() {
        return note != null && note.reminderTime > System.currentTimeMillis();
    }

    private void showReminderDialog() {
        if (note == null || noteId == -1) {
            Toast.makeText(this, R.string.reminder_need_save, Toast.LENGTH_SHORT).show();
            return;
        }
        if (hasActiveReminder()) {
            // Sudah ada -> tawarkan batalkan.
            new IosDialog(this)
                    .setTitle(getString(R.string.reminder_title))
                    .setMessage(getString(R.string.reminder_existing,
                            formatReminder(note.reminderTime), repeatLabel(note.reminderRepeat)))
                    .setPositive(getString(R.string.reminder_cancel), () -> {
                        note.reminderTime = 0;
                        note.reminderRepeat = com.hozinking.notes.reminder
                                .ReminderScheduler.REPEAT_NONE;
                        saveNow();
                        com.hozinking.notes.reminder.ReminderScheduler.cancel(this, noteId);
                    })
                    .setNegative(getString(R.string.cancel), () -> {})
                    .show();
            return;
        }
        // Minta izin notifikasi dulu (Android 13+).
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
        showReminderQuickSheet();
    }

    /**
     * v1.29: preset pengingat cepat ala Google Keep — satu ketuk tanpa
     * melewati pemilih tanggal &amp; jam. Jalur lama tetap tersedia lewat
     * "Pilih tanggal &amp; waktu…". Preset selalu jatuh di masa depan:
     * "Malam ini" yang sudah lewat otomatis geser ke besok malam.
     */
    private void showReminderQuickSheet() {
        new IosActionSheet(this)
                .setTitle(getString(R.string.reminder_quick_title))
                .addItem(getString(R.string.reminder_quick_tomorrow_morning),
                        () -> applyReminderPreset(1, 8, 0))
                .addItem(getString(R.string.reminder_quick_tonight),
                        () -> applyReminderPreset(0, 20, 0))
                .addItem(getString(R.string.reminder_quick_next_monday),
                        this::applyReminderNextMonday)
                .addItem(getString(R.string.reminder_quick_custom),
                        this::showReminderPicker)
                .show();
    }

    /** Terapkan preset: hari ini + dayOffset pada jam:menit yang diminta. */
    private void applyReminderPreset(int dayOffset, int hour, int minute) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.add(java.util.Calendar.DAY_OF_YEAR, dayOffset);
        c.set(java.util.Calendar.HOUR_OF_DAY, hour);
        c.set(java.util.Calendar.MINUTE, minute);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        long when = c.getTimeInMillis();
        if (when <= System.currentTimeMillis()) {
            c.add(java.util.Calendar.DAY_OF_YEAR, 1);
            when = c.getTimeInMillis();
        }
        warnIfExactAlarmDenied();
        note.reminderTime = when;
        showRepeatPicker(when);
    }

    /** Preset Senin depan pukul 09.00 (bila hari ini Senin, berarti 7 hari lagi). */
    private void applyReminderNextMonday() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int days = (java.util.Calendar.MONDAY - c.get(java.util.Calendar.DAY_OF_WEEK) + 7) % 7;
        if (days == 0) days = 7;
        applyReminderPreset(days, 9, 0);
    }

    /** Pemilih tanggal &amp; jam manual (jalur lama, kini lewat preset "kustom"). */
    private void showReminderPicker() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        new android.app.DatePickerDialog(this,
                (dv, y, m, d) -> new android.app.TimePickerDialog(this,
                        (tv, hh, mm) -> {
                            java.util.Calendar t = java.util.Calendar.getInstance();
                            t.set(y, m, d, hh, mm, 0);
                            t.set(java.util.Calendar.MILLISECOND, 0);
                            long when = t.getTimeInMillis();
                            if (when <= System.currentTimeMillis()) {
                                Toast.makeText(this, R.string.reminder_past,
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            warnIfExactAlarmDenied();
                            note.reminderTime = when;
                            showRepeatPicker(when);
                        },
                        c.get(java.util.Calendar.HOUR_OF_DAY),
                        c.get(java.util.Calendar.MINUTE), true)
                        .show(),
                c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH),
                c.get(java.util.Calendar.DAY_OF_MONTH))
                .show();
    }

    /** Peringatan izin alarm tepat waktu (dipakai preset & pemilih manual). */
    private void warnIfExactAlarmDenied() {
        if (!com.hozinking.notes.reminder.ReminderScheduler.canScheduleExact(this)) {
            // Arahkan ke Settings sistem untuk izin exact alarm.
            try {
                startActivity(com.hozinking.notes.reminder
                        .ReminderScheduler.exactAlarmSettingsIntent());
            } catch (Exception ignored) {
            }
            Toast.makeText(this, R.string.reminder_exact_denied,
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Pilih pola ulang pengingat setelah waktu ditentukan. */
    private void showRepeatPicker(long when) {
        new IosActionSheet(this)
                .setTitle(getString(R.string.reminder_repeat_title))
                .addItem(getString(R.string.reminder_repeat_once),
                        () -> applyReminder(when,
                                com.hozinking.notes.reminder.ReminderScheduler.REPEAT_NONE))
                .addItem(getString(R.string.reminder_repeat_daily),
                        () -> applyReminder(when,
                                com.hozinking.notes.reminder.ReminderScheduler.REPEAT_DAILY))
                .addItem(getString(R.string.reminder_repeat_weekly),
                        () -> applyReminder(when,
                                com.hozinking.notes.reminder.ReminderScheduler.REPEAT_WEEKLY))
                .addItem(getString(R.string.reminder_repeat_monthly),
                        () -> applyReminder(when,
                                com.hozinking.notes.reminder.ReminderScheduler.REPEAT_MONTHLY))
                .addItem(getString(R.string.reminder_repeat_yearly),
                        () -> applyReminder(when,
                                com.hozinking.notes.reminder.ReminderScheduler.REPEAT_YEARLY))
                .show();
    }

    private void applyReminder(long when, int repeat) {
        note.reminderTime = when;
        note.reminderRepeat = repeat;
        saveNow();
        com.hozinking.notes.reminder.ReminderScheduler.schedule(this, noteId, when);
        Toast.makeText(this,
                getString(R.string.reminder_set_ok, formatReminder(when), repeatLabel(repeat)),
                Toast.LENGTH_SHORT).show();
    }

    private String repeatLabel(int repeat) {
        if (repeat == com.hozinking.notes.reminder.ReminderScheduler.REPEAT_DAILY) {
            return getString(R.string.reminder_repeat_daily);
        }
        if (repeat == com.hozinking.notes.reminder.ReminderScheduler.REPEAT_WEEKLY) {
            return getString(R.string.reminder_repeat_weekly);
        }
        if (repeat == com.hozinking.notes.reminder.ReminderScheduler.REPEAT_MONTHLY) {
            return getString(R.string.reminder_repeat_monthly);
        }
        if (repeat == com.hozinking.notes.reminder.ReminderScheduler.REPEAT_YEARLY) {
            return getString(R.string.reminder_repeat_yearly);
        }
        return getString(R.string.reminder_repeat_once);
    }

    private String formatReminder(long when) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                "dd MMM yyyy, HH:mm", new java.util.Locale("id", "ID"));
        return f.format(new java.util.Date(when));
    }

    private void showColorSheet() {
        if (note == null) return;
        IosActionSheet s = new IosActionSheet(this)
                .setTitle(getString(R.string.notes_color_title));
        s.addItem(getString(R.string.notes_color_none), () -> setNoteColor(0));
        s.addItem(getString(R.string.notes_color_yellow),
                () -> setNoteColor(ContextCompat.getColor(this, R.color.note_yellow)));
        s.addItem(getString(R.string.notes_color_green),
                () -> setNoteColor(ContextCompat.getColor(this, R.color.note_green)));
        s.addItem(getString(R.string.notes_color_blue),
                () -> setNoteColor(ContextCompat.getColor(this, R.color.note_blue)));
        s.addItem(getString(R.string.notes_color_pink),
                () -> setNoteColor(ContextCompat.getColor(this, R.color.note_pink)));
        s.addItem(getString(R.string.notes_color_purple),
                () -> setNoteColor(ContextCompat.getColor(this, R.color.note_purple)));
        s.show();
    }

    private void setNoteColor(int color) {
        if (note != null) {
            note.color = color;
            saveNow();
        }
    }

    private void showFolderSheet() {
        if (note == null) return;
        NoteRepository.get(this).getFolders(folders -> main.post(() -> {
            IosActionSheet s = new IosActionSheet(this)
                    .setTitle(getString(R.string.notes_folder_title));
            s.addItem(getString(R.string.notes_no_folder), () -> {
                note.folderId = null;
                saveNow();
            });
            if (folders != null) {
                for (FolderEntity f : folders) {
                    s.addItem(f.name, () -> {
                        note.folderId = f.id;
                        saveNow();
                    });
                }
            }
            s.show();
        }));
    }

    private void showTagsDialog() {
        withNoteId(id -> NoteRepository.get(this)
                .getTagsForNote(id, tags -> main.post(() -> {
                    List<String> current = new ArrayList<>();
                    if (tags != null) {
                        for (TagEntity t : tags) current.add(t.name);
                    }
                    LinearLayout layout = new LinearLayout(this);
                    layout.setOrientation(LinearLayout.VERTICAL);
                    int pad = (int) (16 * getResources().getDisplayMetrics().density);
                    layout.setPadding(pad, pad / 2, pad, pad / 2);
                    TextView cur = new TextView(this);
                    cur.setText(current.isEmpty() ? "-" : TextUtils.join(", ", current));
                    cur.setPadding(0, 0, 0, pad / 2);
                    EditText input = new EditText(this);
                    input.setHint(R.string.notes_tags_hint);
                    layout.addView(cur);
                    layout.addView(input);
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.notes_tags_title)
                            .setView(layout)
                            .setPositiveButton(R.string.save, (d, w) -> {
                                for (String p : input.getText().toString().split(",")) {
                                    String t = p.trim();
                                    if (!t.isEmpty() && !current.contains(t)) current.add(t);
                                }
                                NoteRepository.get(this)
                                        .setTagsForNote(id, current, v -> {
                                        });
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                })));
    }

    private void confirmDelete() {
        if (noteId == -1) {
            finish();
            return;
        }
        new IosDialog(this)
                .setTitle(getString(R.string.notes_delete_title))
                .setMessage(getString(R.string.notes_delete_msg))
                .setPositive(getString(R.string.delete), () -> {
                    com.hozinking.notes.reminder.ReminderScheduler.cancel(this, noteId);
                    NoteRepository.get(this)
                            .trashNote(noteId, v -> main.post(this::finish));
                })
                .setNegative(getString(R.string.cancel), () -> {
                })
                .show();
    }
}
