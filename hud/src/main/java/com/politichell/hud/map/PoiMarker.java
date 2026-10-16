package com.politichell.hud.map;

import su.nezushin.nminimap.markers.NMapMarker;
import su.nezushin.nminimap.player.NMapPlayer;

/** Неподвижный значок точки (без поворота) в мировых координатах. Работает и на мини-карте, и на полноэкранной. */
final class PoiMarker extends NMapMarker {
    private final int bx, bz;

    PoiMarker(String icon, double x, double z) {
        super(icon);
        this.bx = (int) Math.floor(x);
        this.bz = (int) Math.floor(z);
    }

    @Override
    public int[] getPositionOnMap(NMapPlayer player) {
        int sc = player.getViewScale();
        return new int[]{((bx - player.getViewBlockX()) * 2) / sc, ((bz - player.getViewBlockZ()) * 2) / sc, 0};
    }
}
