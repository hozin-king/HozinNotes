package com.hozinking.notes.ui;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Html;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.CharacterStyle;
import android.text.style.ParagraphStyle;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.util.Linkify;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.hozinking.notes.BuildConfig;
import com.hozinking.notes.R;
import com.hozinking.notes.data.db.FolderEntity;
import com.hozinking.notes.data.db.ImageRefEntity;
import com.hozinking.notes.data.db.NoteEntity;
import com.hozinking.notes.data.db.TagEntity;
import com.hozinking.notes.data.repo.NoteRepository;
import com.hozinking.notes.ui.dialog.IosActionSheet;
import com.hozinking.notes.ui.dialog.IosDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
    private ActivityResultLauncher<String> galleryLauncher;
    private ActivityResultLauncher<Uri> cameraLauncher;

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

        // URL bisa di-tap langsung dari editor (tanpa merusak kursor/seleksi).
        Linkify.addLinks(contentInput, Linkify.WEB_URLS);
        contentInput.setMovementMethod(LinkTouchMovementMethod.getInstance());

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

        SimpleTextWatcher watcher = new SimpleTextWatcher(s -> scheduleSave());
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
        findViewById(R.id.btn_menu).setOnClickListener(v -> showMenu());

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
            }));
        } else {
            note = newNote();
            loading = false;
            undoRedo.clear();
            undoRedo.setTracking(true);
            updateUndoRedoButtons();
        }
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

    // ---------- auto-save ----------

    private void scheduleSave() {
        if (loading) return;
        saveHandler.removeCallbacks(saveRunnable);
        saveHandler.postDelayed(saveRunnable, 800);
    }

    private void syncFieldsToNote() {
        note.title = titleInput.getText().toString();
        Editable e = contentInput.getText();
        if (hasRichContent(e)) {
            note.contentHtml = true;
            note.content = toHtmlWithTables(e);
        } else {
            note.contentHtml = false;
            note.content = e.toString();
        }
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
            }));
        } else {
            NoteRepository.get(this).updateNote(note, v -> {
            });
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
        super.onPause();
    }

    // ---------- checklist ----------

    private void addChecklistItem() {
        int pos = Math.max(contentInput.getSelectionStart(), 0);
        Editable e = contentInput.getText();
        String prefix = (pos > 0 && e.charAt(pos - 1) != '\n') ? "\n" : "";
        e.insert(pos, prefix + "- [ ] ");
        scheduleSave();
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

    private void refreshImages() {
        if (noteId == -1 || note == null) {
            imageStrip.removeAllViews();
            return;
        }
        NoteRepository.get(this).getImages(noteId, list -> main.post(() -> {
            imageStrip.removeAllViews();
            if (list == null) return;
            note.imageCount = list.size();
            for (ImageRefEntity img : list) {
                View item = getLayoutInflater()
                        .inflate(R.layout.item_editor_image, imageStrip, false);
                ImageView iv = item.findViewById(R.id.img);
                imageStrip.addView(item);
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
                    });
                });
                item.findViewById(R.id.btn_remove)
                        .setOnClickListener(x -> confirmRemoveImage(img));
            }
        }));
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
                            formatReminder(note.reminderTime)))
                    .setPositive(getString(R.string.reminder_cancel), () -> {
                        note.reminderTime = 0;
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
                            if (!com.hozinking.notes.reminder.ReminderScheduler
                                    .canScheduleExact(this)) {
                                // Arahkan ke Settings sistem untuk izin exact alarm.
                                try {
                                    startActivity(com.hozinking.notes.reminder
                                            .ReminderScheduler.exactAlarmSettingsIntent());
                                } catch (Exception ignored) {
                                }
                                Toast.makeText(this, R.string.reminder_exact_denied,
                                        Toast.LENGTH_LONG).show();
                            }
                            note.reminderTime = when;
                            saveNow();
                            com.hozinking.notes.reminder.ReminderScheduler
                                    .schedule(this, noteId, when);
                            Toast.makeText(this,
                                    getString(R.string.reminder_set_ok, formatReminder(when)),
                                    Toast.LENGTH_SHORT).show();
                        },
                        c.get(java.util.Calendar.HOUR_OF_DAY),
                        c.get(java.util.Calendar.MINUTE), true)
                        .show(),
                c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH),
                c.get(java.util.Calendar.DAY_OF_MONTH))
                .show();
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
