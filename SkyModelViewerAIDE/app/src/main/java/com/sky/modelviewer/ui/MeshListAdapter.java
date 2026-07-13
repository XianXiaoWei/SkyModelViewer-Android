package com.sky.modelviewer.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import com.sky.modelviewer.R;
import com.sky.modelviewer.model.MeshCatalogEntry;
import java.util.ArrayList;
import java.util.List;

public class MeshListAdapter extends BaseAdapter {

    public interface OnMeshClickListener {
        void onMeshClick(MeshCatalogEntry entry);
    }

    private List<MeshCatalogEntry> allEntries = new ArrayList<MeshCatalogEntry>();
    private List<MeshCatalogEntry> visibleEntries = new ArrayList<MeshCatalogEntry>();
    private OnMeshClickListener listener;
    private Context context;

    public void setEntries(List<MeshCatalogEntry> entries) {
        allEntries = new ArrayList<MeshCatalogEntry>(entries);
        // Sort: column 1 = mesh (models), column 2 = ktx (textures), column 3 = level (maps)
        java.util.Collections.sort(allEntries, new java.util.Comparator<MeshCatalogEntry>() {
            private int typeOrder(String ft) {
                if ("mesh".equals(ft)) return 0;
                if ("ktx".equals(ft)) return 1;
                if ("level".equals(ft)) return 2;
                return 3;
            }
            @Override
            public int compare(MeshCatalogEntry a, MeshCatalogEntry b) {
                int cmp = typeOrder(a.fileType) - typeOrder(b.fileType);
                if (cmp != 0) return cmp;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        visibleEntries = new ArrayList<MeshCatalogEntry>(allEntries);
        notifyDataSetChanged();
    }

    public void filter(String query) {
        if (query == null || query.trim().isEmpty()) {
            visibleEntries = new ArrayList<MeshCatalogEntry>(allEntries);
        } else {
            visibleEntries = new ArrayList<MeshCatalogEntry>();
            String q = query.toLowerCase();
            for (MeshCatalogEntry entry : allEntries) {
                if (entry.name.toLowerCase().contains(q) ||
                    entry.relativePath.toLowerCase().contains(q) ||
                    entry.category.toLowerCase().contains(q)) {
                    visibleEntries.add(entry);
                }
            }
        }
        // Maintain sorted order: mesh first, then ktx, then level
        java.util.Collections.sort(visibleEntries, new java.util.Comparator<MeshCatalogEntry>() {
            private int typeOrder(String ft) {
                if ("mesh".equals(ft)) return 0;
                if ("ktx".equals(ft)) return 1;
                if ("level".equals(ft)) return 2;
                return 3;
            }
            @Override
            public int compare(MeshCatalogEntry a, MeshCatalogEntry b) {
                int cmp = typeOrder(a.fileType) - typeOrder(b.fileType);
                if (cmp != 0) return cmp;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        notifyDataSetChanged();
    }

    public List<MeshCatalogEntry> getVisibleEntries() {
        return visibleEntries;
    }

    public void setOnMeshClickListener(OnMeshClickListener listener) {
        this.listener = listener;
    }

    @Override
    public int getCount() {
        return visibleEntries.size();
    }

    @Override
    public MeshCatalogEntry getItem(int position) {
        return visibleEntries.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    private GradientDrawable makeTagBackground(int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(4f);
        gd.setColor(color);
        return gd;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (context == null) context = parent.getContext();
        View view = convertView;
        if (view == null) {
            view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.list_item, parent, false);
        }

        MeshCatalogEntry entry = visibleEntries.get(position);
        TextView text = (TextView) view.findViewById(R.id.itemText);
        View colorBar = view.findViewById(R.id.itemColorBar);

        String displayText;
        int barColor;

        if ("level".equals(entry.fileType)) {
            displayText = entry.name.replace("[Level] ", "").replace("[关卡] ", "");
            barColor = Color.parseColor("#3B82F6"); // blue
        } else if ("ktx".equals(entry.fileType)) {
            displayText = entry.name;
            barColor = Color.parseColor("#10B981"); // green
        } else {
            displayText = entry.name;
            barColor = Color.parseColor("#6B7280"); // gray
        }

        text.setText(displayText);
        colorBar.setBackgroundColor(barColor);

        final MeshCatalogEntry entryFinal = entry;
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onMeshClick(entryFinal);
            }
        });

        return view;
    }
}
