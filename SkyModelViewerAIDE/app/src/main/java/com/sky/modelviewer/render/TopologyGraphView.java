package com.sky.modelviewer.render;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.sky.modelviewer.parsing.TgclParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Draws a node topology graph showing references between BstNodes and AutoClumps.
 * - Green nodes = BstNode
 * - Orange nodes = AutoClump (Clump type properties that reference other nodes)
 * - Blue edges = general reference
 * - Red edges = upstream (this node is referenced by another)
 * - Yellow edges = downstream (this node references another)
 */
public class TopologyGraphView extends View {

    public static class TopoNode {
        public int index;
        public String name;
        public String className;
        public boolean isAutoClump;
        public float x, y;
        public float targetX, targetY;
        public List<Integer> references = new ArrayList<>(); // indices this node points to
        public List<Integer> referencedBy = new ArrayList<>(); // indices that point to this
    }

    private List<TopoNode> nodes = new ArrayList<>();
    private Set<Integer> selectedNodes = new HashSet<>();
    private int focusedNodeIndex = -1;
    private Paint nodePaint;
    private Paint edgePaint;
    private Paint textPaint;
    private Paint selectedPaint;
    private float offsetX = 0, offsetY = 0;
    private float scaleFactor = 1f;
    private float lastTouchX, lastTouchY;
    private boolean isDragging = false;
    private float pinchStartDist = 0;
    private float pinchStartScale = 1f;

    public TopologyGraphView(Context context) {
        super(context);
        init();
    }

    public TopologyGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        nodePaint.setStyle(Paint.Style.FILL);

        edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(2f);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(9f);
        textPaint.setColor(Color.WHITE);

        selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectedPaint.setStyle(Paint.Style.STROKE);
        selectedPaint.setStrokeWidth(3f);
        selectedPaint.setColor(Color.YELLOW);
    }

    // Full reference graph data (all nodes, no layout)
    private List<TopoNode> allNodes = new ArrayList<>();
    private TgclParser.TgclFile cachedFile = null;
    private Map<String, Integer> nameToIndex = new HashMap<>();

    public void setGraphData(TgclParser.TgclFile file) {
        cachedFile = file;
        allNodes.clear();
        nodes.clear();
        selectedNodes.clear();
        nameToIndex.clear();

        if (file == null || file.nodes == null) return;

        // Pre-build name→index map for O(1) name lookups
        for (int i = 0; i < file.nodes.size(); i++) {
            nameToIndex.put(file.nodes.get(i).name, i);
        }

        // Build all nodes with their references
        for (int i = 0; i < file.nodes.size(); i++) {
            TgclParser.BstNode bn = file.nodes.get(i);
            TopoNode tn = new TopoNode();
            tn.index = i;
            tn.name = bn.name;
            tn.className = file.classNames.size() > bn.classIndex ?
                file.classNames.get(bn.classIndex) : "Unknown";
            tn.isAutoClump = isAutoClumpNode(bn, file);
            collectReferences(tn, bn, file);
            allNodes.add(tn);
        }

        // Build reverse references
        for (int i = 0; i < allNodes.size(); i++) {
            for (int refIdx : allNodes.get(i).references) {
                if (refIdx >= 0 && refIdx < allNodes.size()) {
                    allNodes.get(refIdx).referencedBy.add(i);
                }
            }
        }

        // Don't build full layout — wait for focus
        invalidate();
    }

    @SuppressWarnings("unchecked")
    private void collectReferences(TopoNode tn, TgclParser.BstNode bn, TgclParser.TgclFile file) {
        List<TgclParser.PropertyDef> props = file.propertiesByClass.size() > bn.classIndex ?
            file.propertiesByClass.get(bn.classIndex) : new ArrayList<>();
        for (TgclParser.PropertyDef prop : props) {
            if (prop.propertyType == 2) { // Clump reference
                String key = "[CLUMP]" + prop.propertyName;
                Object val = bn.properties.get(key);
                if (val == null) val = bn.properties.get(prop.propertyName);
                if (val instanceof String) {
                    int refIdx = resolveReference((String) val, file);
                    if (refIdx >= 0) tn.references.add(refIdx);
                }
            } else if (prop.propertyType == 3) {
                Object arrObj = bn.properties.get(prop.propertyName);
                if (arrObj instanceof Map) {
                    Object clumpData = ((Map<String, Object>) arrObj).get("[CLUMP]data");
                    if (clumpData instanceof List) {
                        for (Object item : (List<?>) clumpData) {
                            int refIdx = resolveReference(String.valueOf(item), file);
                            if (refIdx >= 0) tn.references.add(refIdx);
                        }
                    }
                }
            }
        }
    }

    private int resolveReference(String val, TgclParser.TgclFile file) {
        // Try integer index first
        try {
            int idx = Integer.parseInt(val);
            if (idx >= 0 && idx < file.nodes.size()) return idx;
        } catch (NumberFormatException e) {
            // Not a number — look up by name using pre-built map
            Integer idx = nameToIndex.get(val);
            if (idx != null) return idx;
        }
        return -1;
    }

    /**
     * Show the focused node + N-level upstream/downstream.
     * Depth 1 = immediate upstream/downstream only.
     * Depth 2 = also includes upstream-of-upstream, downstream-of-downstream.
     * etc.
     */
    public void setFocusSubgraph(int focusIndex, int depth) {
        nodes.clear();
        selectedNodes.clear();
        focusedNodeIndex = -1;

        if (focusIndex < 0 || focusIndex >= allNodes.size()) {
            invalidate();
            return;
        }

        // BFS to collect relevant nodes up to N levels deep
        java.util.Set<Integer> relevant = new java.util.HashSet<>();
        java.util.Set<Integer> currentLevel = new java.util.HashSet<>();
        currentLevel.add(focusIndex);
        relevant.add(focusIndex);

        for (int level = 0; level < depth; level++) {
            java.util.Set<Integer> nextLevel = new java.util.HashSet<>();
            for (int idx : currentLevel) {
                if (idx < 0 || idx >= allNodes.size()) continue;
                TopoNode tn = allNodes.get(idx);
                // Downstream: nodes this node references
                for (int refIdx : tn.references) {
                    if (!relevant.contains(refIdx)) {
                        relevant.add(refIdx);
                        nextLevel.add(refIdx);
                    }
                }
                // Upstream: nodes that reference this node
                for (int refIdx : tn.referencedBy) {
                    if (!relevant.contains(refIdx)) {
                        relevant.add(refIdx);
                        nextLevel.add(refIdx);
                    }
                }
            }
            currentLevel = nextLevel;
            if (currentLevel.isEmpty()) break;
        }

        // Safety limit: if too many nodes, cap at 200
        if (relevant.size() > 200) {
            java.util.Set<Integer> capped = new java.util.HashSet<>();
            capped.add(focusIndex);
            for (int idx : relevant) {
                if (capped.size() >= 200) break;
                capped.add(idx);
            }
            relevant = capped;
        }

        // Build subgraph nodes (copy from allNodes, remap references)
        java.util.Map<Integer, Integer> indexMap = new java.util.HashMap<>();
        int pos = 0;
        for (int idx : relevant) {
            TopoNode src = allNodes.get(idx);
            TopoNode copy = new TopoNode();
            copy.index = idx;
            copy.name = src.name;
            copy.className = src.className;
            copy.isAutoClump = src.isAutoClump;
            indexMap.put(idx, pos);
            // Position: focus in center, others in concentric rings
            if (idx == focusIndex) {
                copy.x = 0;
                copy.y = 0;
            } else {
                double angle = (double) pos / relevant.size() * 2 * Math.PI;
                float radius = 60f + (float) Math.random() * 40f;
                copy.x = (float) Math.cos(angle) * radius;
                copy.y = (float) Math.sin(angle) * radius;
            }
            copy.targetX = copy.x;
            copy.targetY = copy.y;
            nodes.add(copy);
            pos++;
        }

        // Remap references within subgraph
        for (int idx : relevant) {
            TopoNode src = allNodes.get(idx);
            TopoNode copy = nodes.get(indexMap.get(idx));
            for (int refIdx : src.references) {
                if (relevant.contains(refIdx)) {
                    copy.references.add(indexMap.get(refIdx));
                }
            }
            for (int refIdx : src.referencedBy) {
                if (relevant.contains(refIdx)) {
                    copy.referencedBy.add(indexMap.get(refIdx));
                }
            }
        }

        focusedNodeIndex = indexMap.get(focusIndex);
        runLayout();
        invalidate();
    }

    /** Old signature for backward compat */
    public void setFocusSubgraph(int focusIndex) {
        setFocusSubgraph(focusIndex, 1);
    }

    private boolean isAutoClumpNode(TgclParser.BstNode node, TgclParser.TgclFile file) {
        // AutoClump nodes are those whose class name contains "Clump" or "Auto"
        String className = file.classNames.size() > node.classIndex ?
            file.classNames.get(node.classIndex) : "";
        return className.contains("Clump") || className.contains("Auto") ||
               className.contains("Group") || className.contains("Container");
    }

    private void runLayout() {
        int n = nodes.size();
        if (n == 0) return;

        // For large graphs, use a grid-based approach instead of O(n²)
        if (n > 80) {
            // Grid layout: place nodes in a grid, cluster connected nodes together
            int cols = (int) Math.ceil(Math.sqrt(n));
            int rows = (int) Math.ceil((double) n / cols);
            float spacing = 60f;

            // Simple circular layout with edges pulling connected nodes close
            for (int i = 0; i < n; i++) {
                TopoNode node = nodes.get(i);
                int row = i / cols;
                int col = i % cols;
                node.x = (col - cols / 2f) * spacing;
                node.y = (row - rows / 2f) * spacing;
            }

            // Light attraction pass (only along edges, no repulsion)
            int iterations = Math.min(20, n / 5);
            float k = 50f;
            float attraction = 0.03f;
            for (int iter = 0; iter < iterations; iter++) {
                for (TopoNode node : nodes) {
                    for (int refIdx : node.references) {
                        if (refIdx < 0 || refIdx >= n) continue;
                        TopoNode other = nodes.get(refIdx);
                        float dx = other.x - node.x;
                        float dy = other.y - node.y;
                        float dist = (float) Math.sqrt(dx * dx + dy * dy);
                        if (dist < 1f) dist = 1f;
                        float force = attraction * (dist - k);
                        float fx = dx / dist * force;
                        float fy = dy / dist * force;
                        node.targetX = node.x + fx;
                        node.targetY = node.y + fy;
                        other.targetX = other.x - fx;
                        other.targetY = other.y - fy;
                    }
                }
                for (TopoNode node : nodes) {
                    node.x = node.x * 0.8f + node.targetX * 0.2f;
                    node.y = node.y * 0.8f + node.targetY * 0.2f;
                }
            }
            return;
        }

        // Standard force-directed layout for small graphs
        int iterations = 30;
        float k = 80f;
        float repulsion = 3000f;
        float attraction = 0.05f;

        for (int iter = 0; iter < iterations; iter++) {
            // Repulsion between all nodes (O(n²) but n <= 80)
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    float dx = nodes.get(i).x - nodes.get(j).x;
                    float dy = nodes.get(i).y - nodes.get(j).y;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    if (dist < 1f) dist = 1f;
                    float force = repulsion / (dist * dist);
                    float fx = dx / dist * force;
                    float fy = dy / dist * force;
                    nodes.get(i).targetX += fx;
                    nodes.get(i).targetY += fy;
                    nodes.get(j).targetX -= fx;
                    nodes.get(j).targetY -= fy;
                }
            }

            // Attraction along edges
            for (TopoNode node : nodes) {
                for (int refIdx : node.references) {
                    if (refIdx < 0 || refIdx >= n) continue;
                    TopoNode other = nodes.get(refIdx);
                    float dx = other.x - node.x;
                    float dy = other.y - node.y;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    if (dist < 1f) dist = 1f;
                    float force = attraction * (dist - k);
                    float fx = dx / dist * force;
                    float fy = dy / dist * force;
                    node.targetX += fx;
                    node.targetY += fy;
                    other.targetX -= fx;
                    other.targetY -= fy;
                }
            }

            // Apply forces with damping
            for (TopoNode node : nodes) {
                node.x = node.x * 0.9f + node.targetX * 0.1f;
                node.y = node.y * 0.9f + node.targetY * 0.1f;
                node.targetX = node.x;
                node.targetY = node.y;
            }
        }
    }

    public void setFocusedNode(int index) {
        focusedNodeIndex = index;
        invalidate();
    }

    public int getNodeFileIndex(int subgraphPos) {
        if (subgraphPos < 0 || subgraphPos >= nodes.size()) return -1;
        return nodes.get(subgraphPos).index;
    }

    public int getFocusedNode() {
        return focusedNodeIndex;
    }

    public void clearSelection() {
        selectedNodes.clear();
        focusedNodeIndex = -1;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float cx = getWidth() / 2f + offsetX;
        float cy = getHeight() / 2f + offsetY;

        canvas.save();
        canvas.scale(scaleFactor, scaleFactor, cx, cy);

        // Draw edges first
        for (int i = 0; i < nodes.size(); i++) {
            TopoNode n = nodes.get(i);
            for (int refIdx : n.references) {
                if (refIdx < 0 || refIdx >= nodes.size()) continue;
                TopoNode target = nodes.get(refIdx);

                // Determine edge color
                boolean isUpstream = (i == focusedNodeIndex); // focused node references this → downstream
                boolean isDownstream = (refIdx == focusedNodeIndex); // this references focused → upstream

                if (isDownstream) {
                    edgePaint.setColor(Color.RED); // upstream: something points to focused
                    edgePaint.setStrokeWidth(3f);
                } else if (isUpstream) {
                    edgePaint.setColor(Color.YELLOW); // downstream: focused points to something
                    edgePaint.setStrokeWidth(3f);
                } else {
                    edgePaint.setColor(0x800000FF); // general reference: semi-transparent blue
                    edgePaint.setStrokeWidth(1.5f);
                }

                canvas.drawLine(cx + n.x, cy + n.y, cx + target.x, cy + target.y, edgePaint);

                // Draw arrowhead
                drawArrow(canvas, cx + n.x, cy + n.y, cx + target.x, cy + target.y, edgePaint.getColor());
            }
        }

        // Draw nodes
        for (int i = 0; i < nodes.size(); i++) {
            TopoNode n = nodes.get(i);
            float nx = cx + n.x;
            float ny = cy + n.y;

            // Node color by type
            if (n.isAutoClump) {
                nodePaint.setColor(Color.parseColor("#FF9800")); // Orange for AutoClump
            } else {
                nodePaint.setColor(Color.parseColor("#4CAF50")); // Green for BstNode
            }

            // Highlight focused node
            float radius = (i == focusedNodeIndex) ? 12f : 8f;
            if (selectedNodes.contains(i)) radius = 10f;

            canvas.drawCircle(nx, ny, radius, nodePaint);

            // Selected/focused ring
            if (i == focusedNodeIndex || selectedNodes.contains(i)) {
                canvas.drawCircle(nx, ny, radius + 3, selectedPaint);
            }

            // Node label (only for focused or small node count)
            if (i == focusedNodeIndex || nodes.size() <= 20) {
                String label = n.name.length() > 15 ? n.name.substring(0, 15) + "…" : n.name;
                textPaint.setTextSize(8f);
                textPaint.setColor(Color.WHITE);
                canvas.drawText(label, nx - textPaint.measureText(label) / 2, ny - radius - 4, textPaint);
                // Class name
                textPaint.setTextSize(7f);
                textPaint.setColor(0xFFAAAAAA);
                canvas.drawText(n.className, nx - textPaint.measureText(n.className) / 2, ny + radius + 10, textPaint);
            }
        }

        canvas.restore();
    }

    private void drawArrow(Canvas canvas, float x1, float y1, float x2, float y2, int color) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1f) return;

        // Position arrow at 80% of the line
        float ax = x1 + dx * 0.8f;
        float ay = y1 + dy * 0.8f;

        // Arrow size
        float arrowLen = 6f;
        float angle = (float) Math.atan2(dy, dx);
        float a1 = angle + (float) Math.PI * 0.8f;
        float a2 = angle - (float) Math.PI * 0.8f;

        Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowPaint.setColor(color);
        arrowPaint.setStyle(Paint.Style.FILL);

        Path path = new Path();
        path.moveTo(ax, ay);
        path.lineTo(ax + (float) Math.cos(a1) * arrowLen, ay + (float) Math.sin(a1) * arrowLen);
        path.lineTo(ax + (float) Math.cos(a2) * arrowLen, ay + (float) Math.sin(a2) * arrowLen);
        path.close();
        canvas.drawPath(path, arrowPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Multi-touch: pinch to zoom
        if (event.getPointerCount() == 2) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_POINTER_DOWN:
                    float dx = event.getX(0) - event.getX(1);
                    float dy = event.getY(0) - event.getY(1);
                    pinchStartDist = (float) Math.sqrt(dx * dx + dy * dy);
                    pinchStartScale = scaleFactor;
                    isDragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    dx = event.getX(0) - event.getX(1);
                    dy = event.getY(0) - event.getY(1);
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    if (pinchStartDist > 0) {
                        scaleFactor = Math.max(0.3f, Math.min(5f, pinchStartScale * dist / pinchStartDist));
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    pinchStartDist = 0;
                    return true;
            }
            return true;
        }

        // Single touch: drag or tap node
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = event.getX();
                lastTouchY = event.getY();

                // Check if touching a node
                float cx = getWidth() / 2f + offsetX;
                float cy = getHeight() / 2f + offsetY;
                float touchRadius = 15f / scaleFactor;
                for (int i = nodes.size() - 1; i >= 0; i--) {
                    TopoNode tn = nodes.get(i);
                    float nx = cx + tn.x * scaleFactor;
                    float ny = cy + tn.y * scaleFactor;
                    float ddx = event.getX() - nx;
                    float ddy = event.getY() - ny;
                    if (ddx * ddx + ddy * ddy < touchRadius * touchRadius * scaleFactor * scaleFactor) {
                        focusedNodeIndex = i;
                        invalidate();
                        if (onNodeClickListener != null) {
                            onNodeClickListener.onNodeClick(i);
                        }
                        return true;
                    }
                }
                isDragging = true;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (isDragging) {
                    offsetX += event.getX() - lastTouchX;
                    offsetY += event.getY() - lastTouchY;
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
                isDragging = false;
                return true;
        }
        return super.onTouchEvent(event);
    }

    public interface OnNodeClickListener {
        void onNodeClick(int nodeIndex);
    }

    private OnNodeClickListener onNodeClickListener;

    public void setOnNodeClickListener(OnNodeClickListener listener) {
        this.onNodeClickListener = listener;
    }

    public String getStatsText() {
        int upCount = 0, downCount = 0;
        if (focusedNodeIndex >= 0 && focusedNodeIndex < nodes.size()) {
            upCount = nodes.get(focusedNodeIndex).referencedBy.size();
            downCount = nodes.get(focusedNodeIndex).references.size();
        }
        return "子图:" + nodes.size() + " | 上游:" + upCount + " 下游:" + downCount;
    }
}
