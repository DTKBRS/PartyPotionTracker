package com.PartyPotionTracker;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.awt.image.BufferedImage;

public class PotionTrackerOverlay extends Overlay
{
    private static final Color UNKNOWN_COLOR = Color.LIGHT_GRAY;
    private static final int FILL_ALPHA = 55;
    private static final int OUTLINE_ALPHA = 220;

    private final Client client;
    private final PotionTrackerPlugin plugin;
    private final ItemManager itemManager;

    @Inject
    public PotionTrackerOverlay(Client client, PotionTrackerPlugin plugin, ItemManager itemManager)
    {
        this.client = client;
        this.plugin = plugin;
        this.itemManager = itemManager;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!plugin.isInRaid() || client.getLocalPlayer() == null)
        {
            return null;
        }

        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null || worldView.getScene() == null)
        {
            return null;
        }

        Scene scene = worldView.getScene();
        Tile[][][] tiles = scene.getTiles();
        if (tiles == null)
        {
            return null;
        }

        Color previousColor = graphics.getColor();
        Stroke previousStroke = graphics.getStroke();
        Composite previousComposite = graphics.getComposite();

        try
        {
            for (int plane = 0; plane < tiles.length; plane++)
            {
                if (tiles[plane] == null)
                {
                    continue;
                }

                for (Tile[] row : tiles[plane])
                {
                    if (row == null)
                    {
                        continue;
                    }

                    for (Tile tile : row)
                    {
                        renderTile(graphics, tile);
                    }
                }
            }
        }
        finally
        {
            graphics.setColor(previousColor);
            graphics.setStroke(previousStroke);
            graphics.setComposite(previousComposite);
        }

        return null;
    }

    private void renderTile(Graphics2D graphics, Tile tile)
    {
        if (tile == null || tile.getGroundItems() == null || tile.getLocalLocation() == null)
        {
            return;
        }

        WorldPoint location = tile.getWorldLocation();
        if (location == null)
        {
            return;
        }

        for (TileItem item : tile.getGroundItems())
        {
            if (!RaidPotions.isPotion(item.getId()))
            {
                continue;
            }

            TrackedItem trackedItem = plugin.getPickupManager().findTrackedItem(item.getId(), location);
            HighlightKind kind = getHighlightKind(item, trackedItem);
            if (!isEnabled(kind))
            {
                continue;
            }

            Color color = getColor(kind, trackedItem);
            drawHighlight(graphics, tile.getLocalLocation(), item, color);
        }
    }

    private HighlightKind getHighlightKind(TileItem item, TrackedItem trackedItem)
    {
        if (item.getOwnership() == TileItem.OWNERSHIP_SELF
                || isLocalOwner(trackedItem))
        {
            return HighlightKind.OWN;
        }

        if (trackedItem != null)
        {
            return HighlightKind.PARTY;
        }

        return HighlightKind.UNKNOWN;
    }

    private boolean isLocalOwner(TrackedItem trackedItem)
    {
        return trackedItem != null
                && trackedItem.getOwnerName() != null
                && client.getLocalPlayer() != null
                && trackedItem.getOwnerName().equalsIgnoreCase(client.getLocalPlayer().getName());
    }

    private boolean isEnabled(HighlightKind kind)
    {
        switch (kind)
        {
            case OWN:
                return plugin.getConfig().highlightOwnPotions();
            case PARTY:
                return plugin.getConfig().highlightPartyPotions();
            case UNKNOWN:
                return plugin.getConfig().highlightUnknownPotions();
            default:
                return false;
        }
    }

    private Color getColor(HighlightKind kind, TrackedItem trackedItem)
    {
        if (kind == HighlightKind.OWN)
        {
            String ownerName = trackedItem == null || trackedItem.getOwnerName() == null
                    ? client.getLocalPlayer() == null ? null : client.getLocalPlayer().getName()
                    : trackedItem.getOwnerName();
            return plugin.getColorForPartyMember(ownerName);
        }

        if (kind == HighlightKind.PARTY && trackedItem != null)
        {
            return plugin.getColorForPartyMember(trackedItem.getOwnerName());
        }

        return UNKNOWN_COLOR;
    }

    private void drawHighlight(Graphics2D graphics, LocalPoint localPoint, TileItem item, Color color)
    {
        PotionTrackerConfig.HighlightStyle style = plugin.getConfig().highlightStyle();
        switch (style)
        {
            case ITEM_OUTLINE:
                drawItemOutline(graphics, localPoint, item, color);
                break;
            case CIRCLE:
                drawCircle(graphics, localPoint, color);
                break;
            case DOT:
                drawDot(graphics, localPoint, color);
                break;
            case TILE_FILL:
                drawTileFill(graphics, localPoint, color);
                break;
            case CORNERS:
                drawCornerMarkers(graphics, localPoint, color);
                break;
            case TILE_OUTLINE:
            default:
                drawTileOutline(graphics, localPoint, color);
                break;
        }
    }

    private void drawItemOutline(Graphics2D graphics, LocalPoint localPoint, TileItem item, Color color)
    {
        BufferedImage outline = itemManager.getItemOutline(item.getId(), item.getQuantity(), color);
        if (outline == null || outline.getWidth() == 0 || outline.getHeight() == 0)
        {
            return;
        }

        Point canvasPoint = Perspective.getCanvasImageLocation(client, localPoint, outline, 0);
        if (canvasPoint == null)
        {
            return;
        }

        graphics.drawImage(outline, canvasPoint.getX(), canvasPoint.getY(), null);
    }

    private void drawTileOutline(Graphics2D graphics, LocalPoint localPoint, Color color)
    {
        Polygon tilePolygon = Perspective.getCanvasTilePoly(client, localPoint);
        if (tilePolygon == null)
        {
            return;
        }

        graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
        graphics.setColor(withAlpha(color, FILL_ALPHA));
        graphics.fillPolygon(tilePolygon);
        graphics.setColor(withAlpha(color, OUTLINE_ALPHA));
        graphics.setStroke(new BasicStroke(plugin.getConfig().outlineWidth()));
        graphics.drawPolygon(tilePolygon);
    }

    private void drawTileFill(Graphics2D graphics, LocalPoint localPoint, Color color)
    {
        Polygon tilePolygon = Perspective.getCanvasTilePoly(client, localPoint);
        if (tilePolygon == null)
        {
            return;
        }

        graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
        graphics.setColor(withAlpha(color, FILL_ALPHA));
        graphics.fillPolygon(tilePolygon);
    }

    private void drawCornerMarkers(Graphics2D graphics, LocalPoint localPoint, Color color)
    {
        Polygon tilePolygon = Perspective.getCanvasTilePoly(client, localPoint);
        if (tilePolygon == null || tilePolygon.npoints < 2)
        {
            return;
        }

        graphics.setColor(withAlpha(color, OUTLINE_ALPHA));
        graphics.setStroke(new BasicStroke(plugin.getConfig().outlineWidth()));

        for (int i = 0; i < tilePolygon.npoints; i++)
        {
            int next = (i + 1) % tilePolygon.npoints;
            int previous = (i + tilePolygon.npoints - 1) % tilePolygon.npoints;
            int x = tilePolygon.xpoints[i];
            int y = tilePolygon.ypoints[i];

            drawCornerSegment(graphics, x, y,
                    tilePolygon.xpoints[next], tilePolygon.ypoints[next]);
            drawCornerSegment(graphics, x, y,
                    tilePolygon.xpoints[previous], tilePolygon.ypoints[previous]);
        }
    }

    private void drawCornerSegment(Graphics2D graphics, int startX, int startY, int endX, int endY)
    {
        int cornerLength = 4;
        int x = startX + (endX - startX) / cornerLength;
        int y = startY + (endY - startY) / cornerLength;
        graphics.drawLine(startX, startY, x, y);
    }

    private void drawCircle(Graphics2D graphics, LocalPoint localPoint, Color color)
    {
        Point canvasPoint = Perspective.localToCanvas(client, localPoint, 0);
        if (canvasPoint == null)
        {
            return;
        }

        int radius = 8;
        graphics.setColor(withAlpha(color, FILL_ALPHA));
        graphics.fillOval(canvasPoint.getX() - radius, canvasPoint.getY() - radius, radius * 2, radius * 2);
        graphics.setColor(withAlpha(color, OUTLINE_ALPHA));
        graphics.setStroke(new BasicStroke(plugin.getConfig().outlineWidth()));
        graphics.drawOval(canvasPoint.getX() - radius, canvasPoint.getY() - radius, radius * 2, radius * 2);
    }

    private void drawDot(Graphics2D graphics, LocalPoint localPoint, Color color)
    {
        Point canvasPoint = Perspective.localToCanvas(client, localPoint, 0);
        if (canvasPoint == null)
        {
            return;
        }

        int radius = 4;
        graphics.setColor(withAlpha(color, OUTLINE_ALPHA));
        graphics.fillOval(canvasPoint.getX() - radius, canvasPoint.getY() - radius, radius * 2, radius * 2);
    }

    private static Color withAlpha(Color color, int alpha)
    {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private enum HighlightKind
    {
        OWN,
        PARTY,
        UNKNOWN
    }
}
