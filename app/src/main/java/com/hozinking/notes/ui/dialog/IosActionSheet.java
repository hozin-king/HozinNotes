package com.hozinking.notes.ui.dialog;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.hozinking.notes.R;

import java.util.ArrayList;
import java.util.List;

/** Action sheet gaya iOS memakai BottomSheetDialog. */
public class IosActionSheet {

    private static class Item {
        final String label;
        final Runnable action;
        final boolean destructive;

        Item(String label, Runnable action, boolean destructive) {
            this.label = label;
            this.action = action;
            this.destructive = destructive;
        }
    }

    private final Context ctx;
    private String title;
    private final List<Item> items = new ArrayList<>();

    public IosActionSheet(Context ctx) {
        this.ctx = ctx;
    }

    public IosActionSheet setTitle(String t) {
        this.title = t;
        return this;
    }

    public IosActionSheet addItem(String label, Runnable r) {
        items.add(new Item(label, r, false));
        return this;
    }

    public IosActionSheet addDestructive(String label, Runnable r) {
        items.add(new Item(label, r, true));
        return this;
    }

    public void show() {
        final BottomSheetDialog dialog = new BottomSheetDialog(ctx);
        View root = LayoutInflater.from(ctx).inflate(R.layout.layout_ios_sheet, null);
        dialog.setContentView(root);

        TextView tvTitle = root.findViewById(R.id.sheet_title);
        if (title != null && !title.isEmpty()) {
            tvTitle.setVisibility(View.VISIBLE);
            tvTitle.setText(title);
        } else {
            tvTitle.setVisibility(View.GONE);
        }

        LinearLayout container = root.findViewById(R.id.sheet_items);
        LayoutInflater inflater = LayoutInflater.from(ctx);
        for (Item item : items) {
            View itemView = inflater.inflate(R.layout.item_ios_sheet, container, false);
            TextView label = itemView.findViewById(R.id.sheet_label);
            label.setText(item.label);
            if (item.destructive) {
                label.setTextColor(ContextCompat.getColor(ctx, R.color.danger));
            }
            final Runnable action = item.action;
            itemView.setOnClickListener(v -> {
                dialog.dismiss();
                if (action != null) {
                    action.run();
                }
            });
            container.addView(itemView);
        }

        TextView cancel = root.findViewById(R.id.sheet_cancel);
        cancel.setText(ctx.getString(R.string.cancel));
        cancel.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }
}
