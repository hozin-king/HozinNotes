package com.hozinking.notes.ui.mindmap;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

import com.hozinking.notes.R;
import com.hozinking.notes.data.db.FolderEntity;
import com.hozinking.notes.data.db.TagEntity;
import com.hozinking.notes.data.repo.NoteRepository;
import com.hozinking.notes.ui.dialog.IosDialog;

import java.util.List;

/**
 * Layar mindmap fullscreen.
 *
 * <p>Keputusan desain (jujur, tanpa mengarang API): {@link NoteRepository} saat ini
 * hanya menyediakan {@code getFolders} / {@code getTags} sebagai list — tidak ada
 * query "N catatan terbaru per folder". Maka mindmap menampilkan hierarki
 * Folder + Tag sebagai node; ketuk node membuka dialog info (pengelolaan lewat
 * tab Folder). Anak catatan per folder bisa ditambahkan nanti bila repo
 * menyediakan API list/top-N.
 */
public class MindmapActivity extends AppCompatActivity {

    private MindmapView mindmapView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        setContentView(R.layout.activity_mindmap);

        mindmapView = findViewById(R.id.mindmap);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        NoteRepository repo = NoteRepository.get(this);
        // Callback repo berjalan di background thread -> post ke main.
        repo.getFolders(folders -> repo.getTags(tags ->
                new Handler(Looper.getMainLooper()).post(() ->
                        mindmapView.setData(folders, tags, nodeListener()))));
    }

    private MindmapView.OnNodeClick nodeListener() {
        return new MindmapView.OnNodeClick() {
            @Override
            public void onFolder(FolderEntity folder) {
                new IosDialog(MindmapActivity.this)
                        .setTitle(folder.name)
                        .setMessage(getString(R.string.mindmap_folder_info))
                        .setPositive(getString(R.string.ok), null)
                        .show();
            }

            @Override
            public void onTag(TagEntity tag) {
                new IosDialog(MindmapActivity.this)
                        .setTitle(tag.name)
                        .setMessage(getString(R.string.mindmap_tag_info))
                        .setPositive(getString(R.string.ok), null)
                        .show();
            }
        };
    }
}
