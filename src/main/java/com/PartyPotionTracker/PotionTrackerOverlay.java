package com.PartyPotionTracker;

import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

import java.awt.*;

import javax.inject.Inject;
import java.util.List;

public class PotionTrackerOverlay extends Overlay {
    private final Client client;
    private final PotionTrackerPlugin plugin;
    private final ModelOutlineRenderer modelOutlineRenderer;

    @Inject
    public PotionTrackerOverlay(
            Client client, PotionTrackerPlugin plugin, ModelOutlineRenderer modelOutlineRenderer) {

        this.client = client;
        this.plugin = plugin;
        this.modelOutlineRenderer = modelOutlineRenderer;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (plugin.isSupportedRaid()
                && (plugin.getConfig().highlightOwnSupplies()
                        || plugin.getConfig().highlightOtherSupplies())
                && plugin.getPickupManager() != null) {
            drawVisibleSupplies(graphics);
        }
        return null;
    }

    private void drawVisibleSupplies(Graphics2D graphics) {
        if (client.getTopLevelWorldView() == null || client.getLocalPlayer() == null) return;
        Tile[][][] tiles = client.getTopLevelWorldView().getScene().getTiles();
        for (Tile[][] plane : tiles) {
            for (Tile[] row : plane) {
                for (Tile tile : row) {
                    if (tile == null || tile.getGroundItems() == null) continue;
                    WorldPoint location = tile.getWorldLocation();
                    if (location == null
                            || client.getLocalPlayer().getWorldLocation().distanceTo(location) > 32)
                        continue;
                    for (TileItem item : tile.getGroundItems()) {
                        if (!RaidSupplies.isTrackable(client, item.getId())) continue;
                        LocalPoint localPoint = tile.getLocalLocation();
                        Point canvasPoint =
                                Perspective.getCanvasTextLocation(client, graphics, localPoint, ".", 0);
                        if (canvasPoint == null) continue;
                        Color color = colorFor(location, item.getId());
                        if (color == null) continue;
                        graphics.setColor(color);
                        drawHighlight(graphics, tile, localPoint, canvasPoint, item, color);
                    }
                }
            }
        }
    }

    private void drawHighlight(
            Graphics2D graphics,
            Tile tile,
            LocalPoint localPoint,
            Point canvasPoint,
            TileItem item,
            Color color) {
        SupplyHighlightStyle style = plugin.getConfig().supplyHighlightStyle();
        if (style == null) style = SupplyHighlightStyle.DOT;
        switch (style) {
            case ITEM_OUTLINE:
                if (tile.getItemLayer() != null) {
                    int outlineWidth = Math.max(1, plugin.getConfig().potionOutlineWidth());
                    modelOutlineRenderer.drawOutline(tile.getItemLayer(), item, outlineWidth, color, 4);
                    break;
                }
                // Fall back to the ground tile if this scene has no item layer to outline.
            case TILE_OUTLINE:
                Polygon outline = Perspective.getCanvasTilePoly(client, localPoint);
                if (outline != null) {
                    graphics.setColor(color);
                    graphics.drawPolygon(outline);
                }
                break;
            case TILE_FILL:
                Polygon fill = Perspective.getCanvasTilePoly(client, localPoint);
                if (fill != null) {
                    Color translucent = new Color(color.getRed(), color.getGreen(), color.getBlue(), 70);
                    graphics.setColor(translucent);
                    graphics.fillPolygon(fill);
                    graphics.setColor(color);
                    graphics.drawPolygon(fill);
                }
                break;
            case LABEL:
                String name = client.getItemDefinition(item.getId()).getName();
                String text = item.getQuantity() > 1 ? name + " x" + item.getQuantity() : name;
                Point label = Perspective.getCanvasTextLocation(client, graphics, localPoint, text, 0);
                if (label != null) {
                    graphics.setColor(color);
                    graphics.drawString(text, label.getX(), label.getY());
                }
                break;
            case DOT:
            default:
                graphics.setColor(color);
                graphics.fillOval(canvasPoint.getX() - 3, canvasPoint.getY() - 3, 6, 6);
                break;
        }
    }

    private Color colorFor(WorldPoint location, int itemId) {
        List<TrackedItem> tracked = plugin.getPickupManager().getMyDroppedItems();
        for (TrackedItem item : tracked) {
            if (item.getItemId() == itemId && location.equals(item.getWorldPoint())) {
                boolean own = client.getLocalPlayer() != null
                        && item.getOwnerName() != null
                        && item.getOwnerName().equalsIgnoreCase(client.getLocalPlayer().getName());
                if (own && !plugin.getConfig().highlightOwnSupplies()) return null;
                if (!own && !plugin.getConfig().highlightOtherSupplies()) return null;
                return plugin.getPickupManager().isOwnershipUncertain(item)
                        ? Color.GRAY
                        : plugin.getColorForPartyMember(item.getOwnerName());
            }
        }
        return plugin.getConfig().highlightOtherSupplies() ? Color.GRAY : null;
    }
}
