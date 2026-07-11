package com.sky.modelviewer.ui;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
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

    public void setEntries(List<MeshCatalogEntry> entries) {
        allEntries = entries;
        visibleEntries = new ArrayList<MeshCatalogEntry>(entries);
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

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = LayoutInflater.from(parent.getContext())
                .inflate(android.R.layout.simple_list_item_1, parent, false);
        }

        MeshCatalogEntry entry = visibleEntries.get(position);
        TextView text = (TextView) view.findViewById(android.R.id.text1);

        // Show category prefix + name for clarity
        String displayText;
        if ("level".equals(entry.fileType)) {
            displayText = "[Level] " + entry.name.replace("[Level] ", "");
            text.setTextColor(Color.parseColor("#1F55BC"));
        } else if ("ktx".equals(entry.fileType)) {
            displayText = "[Texture] " + entry.name;
            text.setTextColor(Color.parseColor("#059669"));
        } else {
            displayText = "[" + entry.category + "] " + entry.name;
            text.setTextColor(Color.parseColor("#111827"));
        }
        text.setText(displayText);
        text.setPadding(32, 16, 16, 16);
        text.setTextSize(13f);

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
