package com.hozinking.notes.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;

/**
 * Placeholder visual untuk tabel di editor: digambar sebagai kartu
 * "Tabel RxC — ketuk untuk edit". HTML asli tabel disimpan di {@link #html}
 * dan ditulis kembali saat catatan disimpan.
 */
public class TableSpan extends ReplacementSpan {

    public String html;
    public int rows;
    public int cols;

    private String label;

    public TableSpan(String html, int rows, int cols) {
        this.html = html;
        this.rows = rows;
        this.cols = cols;
        refreshLabel();
    }

    /** Perbarui label setelah edit (baris/kolom berubah). */
    public void refreshLabel() {
        this.label = "▦  Tabel " + rows + "×" + cols + "  —  ketuk untuk edit";
    }

    @Override
    public int getSize(@NonNull Paint paint, CharSequence text,
                       int start, int end, Paint.FontMetricsInt fm) {
        return Math.round(paint.measureText(label)) + 48;
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                     float x, int top, int y, int bottom, @NonNull Paint paint) {
        float w = getSize(paint, text, start, end, null);
        Paint bg = new Paint(paint);
        bg.setColor(0xFFE8EEF7);
        RectF r = new RectF(x + 4, top + 4, x + w - 4, bottom - 4);
        canvas.drawRoundRect(r, 18f, 18f, bg);
        Paint tp = new Paint(paint);
        tp.setColor(0xFF2B4A7A);
        canvas.drawText(label, x + 28, y, tp);
    }

    /** Bangun HTML <table> dari grid sel. */
    public static String buildHtml(String[][] cells) {
        StringBuilder sb = new StringBuilder("<table border=\"1\">");
        for (String[] row : cells) {
            sb.append("<tr>");
            for (String c : row) {
                sb.append("<td>").append(escape(c)).append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    public static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Hitung baris dari HTML tabel. */
    public static int countRows(String html) {
        int n = 0, i = -1;
        String low = html.toLowerCase();
        while ((i = low.indexOf("<tr", i + 1)) >= 0) n++;
        return Math.max(n, 1);
    }

    /** Hitung kolom (maks sel per baris) dari HTML tabel. */
    public static int countCols(String html) {
        String low = html.toLowerCase();
        int best = 1;
        int pos = 0;
        while (true) {
            int tr = low.indexOf("<tr", pos);
            if (tr < 0) break;
            int end = low.indexOf("</tr>", tr);
            if (end < 0) end = low.length();
            String row = low.substring(tr, end);
            int c = 0, j = -1;
            while ((j = row.indexOf("<td", j + 1)) >= 0) c++;
            j = -1;
            while ((j = row.indexOf("<th", j + 1)) >= 0) c++;
            if (c > best) best = c;
            pos = end + 5;
        }
        return best;
    }

    /** Ambil isi sel sebagai grid [baris][kolom] (teks polos). */
    public static String[][] parseCells(String html) {
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        String low = html.toLowerCase();
        int pos = 0;
        while (true) {
            int tr = low.indexOf("<tr", pos);
            if (tr < 0) break;
            int end = low.indexOf("</tr>", tr);
            if (end < 0) break;
            String rowHtml = html.substring(tr, end);
            String rowLow = low.substring(tr, end);
            java.util.List<String> cells = new java.util.ArrayList<>();
            int cp = 0;
            while (true) {
                int td = rowLow.indexOf("<td", cp);
                int th = rowLow.indexOf("<th", cp);
                int c = td >= 0 && (th < 0 || td < th) ? td : th;
                if (c < 0) break;
                int gt = rowLow.indexOf('>', c);
                if (gt < 0) break;
                int close = rowLow.indexOf("</t", gt);
                String cell = close >= 0 ? rowHtml.substring(gt + 1, close) : "";
                cell = cell.replaceAll("(?s)<[^>]*>", "")
                        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
                cells.add(cell.trim());
                cp = gt + 1;
                if (close < 0) break;
            }
            rows.add(cells.toArray(new String[0]));
            pos = end + 5;
        }
        int cols = 1;
        for (String[] r : rows) cols = Math.max(cols, r.length);
        String[][] out = new String[Math.max(rows.size(), 1)][cols];
        for (int i = 0; i < out.length; i++) {
            for (int j = 0; j < cols; j++) {
                out[i][j] = (i < rows.size() && j < rows.get(i).length) ? rows.get(i)[j] : "";
            }
        }
        return out;
    }
}
