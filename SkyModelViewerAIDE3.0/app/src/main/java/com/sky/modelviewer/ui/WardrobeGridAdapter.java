package com.sky.modelviewer.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import com.sky.modelviewer.R;
import com.sky.modelviewer.model.MeshCatalogEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class WardrobeGridAdapter extends BaseAdapter {
    private List<MeshCatalogEntry> allEntries = new ArrayList<MeshCatalogEntry>();
    private List<MeshCatalogEntry> visibleEntries = new ArrayList<MeshCatalogEntry>();
    private Context context;
    private OnMeshClickListener listener;

    private Map<String, String> iconNames = new HashMap<>();
    private Map<String, Bitmap> iconBitmaps = new HashMap<>();
    private Set<String> loadingIcons = new HashSet<>();

    public interface IconLoader {
        Bitmap loadIcon(String iconName);
    }
    private IconLoader iconLoader;
    private Handler handler = new Handler();
    private ExecutorService iconExecutor = Executors.newSingleThreadExecutor();

    public interface OnMeshClickListener {
        void onMeshClick(MeshCatalogEntry entry);
    }

    public WardrobeGridAdapter(Context context) {
        this.context = context;
    }

    public void setListener(OnMeshClickListener listener) {
        this.listener = listener;
    }

    public void setIconLoader(IconLoader loader) {
        this.iconLoader = loader;
    }

    public void setEntries(List<MeshCatalogEntry> entries) {
        allEntries = new ArrayList<MeshCatalogEntry>(entries);
        visibleEntries = new ArrayList<MeshCatalogEntry>(allEntries);
        notifyDataSetChanged();
    }

    public void setIconNames(Map<String, String> names) {
        iconNames = names != null ? names : new HashMap<String, String>();
    }

    /**
     * Clear all icon caches (bitmaps + loading set).
     * Called when APK changes to avoid stale icons.
     */
    public void clearIconCache() {
        iconBitmaps.clear();
        loadingIcons.clear();
    }

    public void filter(String query) {
        if (query == null || query.trim().isEmpty()) {
            visibleEntries = new ArrayList<MeshCatalogEntry>(allEntries);
        } else {
            visibleEntries = new ArrayList<MeshCatalogEntry>();
            String q = query.toLowerCase();
            for (MeshCatalogEntry entry : allEntries) {
                if (entry.name.toLowerCase().contains(q)) {
                    visibleEntries.add(entry);
                }
            }
        }
        notifyDataSetChanged();
    }

    public List<MeshCatalogEntry> getAllEntries() {
        return allEntries;
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
        ViewHolder holder;
        if (convertView == null) {
            LayoutInflater inflater = LayoutInflater.from(context);
            convertView = inflater.inflate(R.layout.wardrobe_grid_item, parent, false);
            holder = new ViewHolder();
            holder.icon = (ImageView) convertView.findViewById(R.id.gridIcon);
            holder.name = (TextView) convertView.findViewById(R.id.gridName);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        final MeshCatalogEntry entry = visibleEntries.get(position);
        
        // Special handling for "clear" item
        boolean isClearItem = "clear".equals(entry.fileType) || "__CLEAR__".equals(entry.name);
        if (isClearItem) {
            holder.name.setText("✕ 清除");
            holder.icon.setImageResource(R.drawable.ic_clear_wardrobe);
        } else {
            holder.name.setText(entry.name);
            // Load icon — cache key is entry.name (not iconName) because different
            // outfits may share the same icon but have different HSV tints.
            // iconNames map: entry.name → iconName (tells us this entry has an icon)
            String iconName = iconNames.get(entry.name);
            if (iconName != null && !iconName.isEmpty()) {
                Bitmap cached = iconBitmaps.get(entry.name);
                if (cached != null) {
                    holder.icon.setImageBitmap(cached);
                } else {
                    holder.icon.setImageResource(R.drawable.ic_mesh_placeholder);
                    // Queue async load only if not already loading
                    synchronized (loadingIcons) {
                        if (!loadingIcons.contains(entry.name) && iconLoader != null) {
                            loadingIcons.add(entry.name);
                            final String loadKey = entry.name;
                            iconExecutor.execute(new Runnable() {
                                @Override
                                public void run() {
                                    Bitmap bmp = null;
                                    try {
                                        bmp = iconLoader.loadIcon(loadKey);
                                    } catch (Exception e) {
                                        // ignore
                                    }
                                    if (bmp != null) {
                                        iconBitmaps.put(loadKey, bmp);
                                    }
                                    synchronized (loadingIcons) {
                                        loadingIcons.remove(loadKey);
                                    }
                                    handler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            notifyDataSetChanged();
                                        }
                                    });
                                }
                            });
                        }
                    }
                }
            } else {
                holder.icon.setImageResource(R.drawable.ic_mesh_placeholder);
            }
        }

        final int pos = position;
        convertView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null && pos < visibleEntries.size()) {
                    listener.onMeshClick(visibleEntries.get(pos));
                }
            }
        });

        return convertView;
    }

    private static class ViewHolder {
        ImageView icon;
        TextView name;
    }
}
