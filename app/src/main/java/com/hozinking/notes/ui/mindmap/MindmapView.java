package com.hozinking.notes.ui.mindmap;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.hozinking.notes.R;
import com.hozinking.notes.data.db.FolderEntity;
import com.hozinking.notes.data.db.TagEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Mindmap dua cabang ("Folder" dan "Tag") di sekitar node tengah "Semua Catatan".
 *
 * <p>Catatan desain: repo saat ini hanya menyediakan list folder/tag (tanpa query
 * top-N catatan per folder), sehingga anak catatan tidak digambar — node folder/tag
 * diklik untuk info. Lihat {@link MindmapActivity}.
 */
public class MindmapView extends View {

    /** Klik pada node. */
    public interface OnNodeClick {
        void onFolder(FolderEntity folder);

        void onTag(TagEntity tag);
    }

    private static class Node {
        String label;
        int color;
        float x, y;      // koordinat konten
        float radius;
        FolderEntity folder; // null bila node cabang/akar
        TagEntity tag;
        Node parent;
    }

    private final List<Node> nodes = new ArrayList<>();
    private Node rootNode;
    private OnNodeClick listener;

    private float panX, panY;
    private float scale = 1f;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector tapDetector;

    private final Paint nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rootPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float lastTouchX, lastTouchY;
    private boolean dragging;
    private final int touchSlop;

    public MindmapView(Context context) {
        this(context, null);
    }

    public MindmapView(Context context, AttributeSet attrs) {
        super(context, attrs);

        nodePaint.setStyle(Paint.Style.FILL);
        nodePaint.setShadowLayer(8f, 0f, 4f, 0x40000000);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(5f);
        linePaint.setColor(0xFF9AA3B8);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(34f);

        rootPaint.setStyle(Paint.Style.FILL);
        rootPaint.setColor(0xFF8B5CF6); // brand_primary (lavender)
        rootPaint.setShadowLayer(10f, 0f, 5f, 0x50000000);

        touchSlop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        scale = Math.max(0.5f, Math.min(2.5f, scale * detector.getScaleFactor()));
                        invalidate();
                        return true;
                    }
                });

        tapDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        return handleTap(e.getX(), e.getY());
                    }
                });
    }

    /**
     * Isi data mindmap. Cabang kiri = folder, cabang kanan = tag.
     */
    public void setData(List<FolderEntity> folders, List<TagEntity> tags, OnNodeClick l) {
        listener = l;
        nodes.clear();

        rootNode = new Node();
        rootNode.label = getContext().getString(R.string.mindmap_root);
        rootNode.color = 0xFF8B5CF6;
        rootNode.radius = 120f;
        rootNode.x = 0f;
        rootNode.y = 0f;
        nodes.add(rootNode);

        if ((folders == null || folders.isEmpty()) && (tags == null || tags.isEmpty())) {
            invalidate();
            return;
        }

        Node folderBranch = branch(getContext().getString(R.string.mindmap_branch_folders),
                -520f, rootNode);
        Node tagBranch = branch(getContext().getString(R.string.mindmap_branch_tags),
                520f, rootNode);

        layoutChildren(folderBranch, folders == null ? new ArrayList<>() : folders, true);
        layoutChildrenTags(tagBranch, tags == null ? new ArrayList<>() : tags);

        invalidate();
    }

    private Node branch(String label, float x, Node parent) {
        Node n = new Node();
        n.label = label;
        n.color = 0xFF2DD4BF; // brand_accent
        n.radius = 95f;
        n.x = x;
        n.y = 0f;
        n.parent = parent;
        nodes.add(n);
        return n;
    }

    private void layoutChildren(Node branch, List<FolderEntity> folders, boolean isFolder) {
        float gap = 260f;
        float startY = -((folders.size() - 1) * gap) / 2f;
        for (int i = 0; i < folders.size(); i++) {
            FolderEntity f = folders.get(i);
            Node n = new Node();
            n.label = shortLabel(f.name);
            n.color = f.color != 0 ? f.color : 0xFF7C8CF8;
            n.radius = 78f;
            n.x = branch.x + (branch.x < 0 ? -360f : 360f);
            n.y = startY + i * gap;
            n.parent = branch;
            if (isFolder) {
                n.folder = f;
            }
            nodes.add(n);
        }
    }

    private void layoutChildrenTags(Node branch, List<TagEntity> tags) {
        float gap = 260f;
        float startY = -((tags.size() - 1) * gap) / 2f;
        for (int i = 0; i < tags.size(); i++) {
            TagEntity t = tags.get(i);
            Node n = new Node();
            n.label = shortLabel(t.name);
            n.color = 0xFFFFB020; // warning
            n.radius = 78f;
            n.x = branch.x + 360f;
            n.y = startY + i * gap;
            n.parent = branch;
            n.tag = t;
            nodes.add(n);
        }
    }

    private String shortLabel(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 14 ? s.substring(0, 13) + "…" : s;
    }

    private boolean handleTap(float sx, float sy) {
        float cx = (sx - getWidth() / 2f - panX) / scale;
        float cy = (sy - getHeight() / 2f - panY) / scale;
        for (Node n : nodes) {
            float dx = cx - n.x;
            float dy = cy - n.y;
            if (dx * dx + dy * dy <= n.radius * n.radius) {
                if (listener != null) {
                    if (n.folder != null) {
                        listener.onFolder(n.folder);
                    } else if (n.tag != null) {
                        listener.onTag(n.tag);
                    }
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        tapDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 1) {
                    float dx = event.getX() - lastTouchX;
                    float dy = event.getY() - lastTouchY;
                    if (!dragging
                            && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        dragging = true;
                    }
                    if (dragging) {
                        panX += dx;
                        panY += dy;
                        invalidate();
                    }
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.translate(getWidth() / 2f + panX, getHeight() / 2f + panY);
        canvas.scale(scale, scale);

        if (nodes.isEmpty()) {
            textPaint.setColor(0xFF6B7280);
            canvas.drawText(getContext().getString(R.string.mindmap_empty), 0f, 0f, textPaint);
            canvas.restore();
            return;
        }

        // Garis lengkung dulu (di bawah node).
        Path path = new Path();
        for (Node n : nodes) {
            if (n.parent == null) {
                continue;
            }
            path.reset();
            path.moveTo(n.parent.x, n.parent.y);
            float midX = (n.parent.x + n.x) / 2f;
            path.quadTo(midX, n.parent.y, n.x, n.y);
            canvas.drawPath(path, linePaint);
        }

        // Node.
        for (Node n : nodes) {
            Paint p = (n == rootNode) ? rootPaint : nodePaint;
            if (n != rootNode) {
                nodePaint.setColor(n.color);
            }
            canvas.drawCircle(n.x, n.y, n.radius, p);

            // Label: bagi jadi 2 baris bila terlalu panjang.
            String label = n.label;
            float maxW = n.radius * 1.6f;
            if (textPaint.measureText(label) > maxW && label.contains(" ")) {
                int mid = label.length() / 2;
                int space = label.indexOf(' ', mid - 3);
                if (space < 0) {
                    space = mid;
                }
                canvas.drawText(label.substring(0, space), n.x, n.y - 12f, textPaint);
                canvas.drawText(label.substring(Math.min(space + 1, label.length())),
                        n.x, n.y + 30f, textPaint);
            } else {
                canvas.drawText(label, n.x, n.y + 12f, textPaint);
            }
        }

        canvas.restore();
    }
}
