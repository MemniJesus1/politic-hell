package me.politichell.engine.map;

/**
 * PoliticalOverlayRenderer — стык AHO for политический слой карты (HOI4-стиль).
 *
 * Использовать в твоём MapManager (NMinimap-PH форк), где ты проходишь по пикселям чанка:
 *   int base = ...;                     // твоё ванильное ландшафтовое значение
 *   ChunkClaim claim = bridge.getClaimAt(world, chunkX, chunkZ);
 *   boolean isBorder = bridge.isBorder(world, chunkX, chunkZ);
 *   int rgb = PoliticalOverlayRenderer.applyPoliticalTint(
 *               bridge.nationKeyOf(viewer.getUniqueId()),
 *               layerMode, base, claim, isBridge);
 *
 *  - внутри территории: заливка с альфой 32% (видно дороги/леса сквозь политическую окраску);
 *  - на границе: плотная линия 82% — читается как граница фронта.
 */
public final class PoliticalOverlayRenderer {

    private PoliticalOverlayRenderer() {}

    public enum Mode { ALL, OWN, RELATIONS, OFF }

    public static int applyPoliticalTint(String viewerNationKey, Mode mode,
                                         int baseRgb, TownyTerritoryBridge.ChunkClaim claim,
                                         boolean isBorderPixel) {
        if (mode == Mode.OFF || claim == null || claim.nationKey() == null
                || claim.nationKey().isBlank()) {
            return baseRgb;
        }

        int overlay;
        switch (mode) {
            case OWN -> {
                if (viewerNationKey == null || !viewerNationKey.equals(claim.nationKey())) {
                    return baseRgb;
                }
                overlay = 0x2ECC71;
            }
            case RELATIONS -> {
                if (viewerNationKey != null && viewerNationKey.equals(claim.nationKey())) {
                    overlay = 0x2ECC71;
                } else {
                    overlay = 0xD4AF37;
                }
            }
            case ALL -> overlay = TownyTerritoryBridge.nationHashColor(claim.nationKey());
            default -> {
                return baseRgb;
            }
        }

        float alpha = isBorderPixel ? 0.82f : 0.32f;
        return blendRgb(baseRgb, overlay, alpha);
    }

    public static int blendRgb(int base, int tint, float alpha) {
        int r1 = (base >> 16) & 0xFF, g1 = (base >> 8) & 0xFF, b1 = base & 0xFF;
        int r2 = (tint >> 16) & 0xFF, g2 = (tint >> 8) & 0xFF, b2 = tint & 0xFF;
        int r = Math.min(255, (int) (r1 * (1f - alpha) + r2 * alpha));
        int g = Math.min(255, (int) (g1 * (1f - alpha) + g2 * alpha));
        int b = Math.min(255, (int) (b1 * (1f - alpha) + b2 * alpha));
        return (r << 16) | (g << 8) | b;
    }
}
