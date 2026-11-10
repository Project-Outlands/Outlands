package net.minecraftforge.client.render.culling.geometry;

import grondag.bitraster.PackedBox;

public final class RenderableBounds {
    private int minX = 16, minY = 16, minZ = 16;
    private int maxX = -1, maxY = -1, maxZ = -1;

    public void mark(int x, int y, int z) {
        if (x < this.minX) this.minX = x;
        if (y < this.minY) this.minY = y;
        if (z < this.minZ) this.minZ = z;
        if (x > this.maxX) this.maxX = x;
        if (y > this.maxY) this.maxY = y;
        if (z > this.maxZ) this.maxZ = z;
    }

    public boolean isEmpty() {
        return this.maxX < 0;
    }

    public int packed() {
        if (isEmpty()) {
            return PackedBox.FULL_BOX;
        }

        return PackedBox.pack(
                Math.max(0, this.minX - 1), Math.max(0, this.minY - 1), Math.max(0, this.minZ - 1),
                Math.min(16, this.maxX + 2), Math.min(16, this.maxY + 2), Math.min(16, this.maxZ + 2),
                PackedBox.RANGE_EXTREME
        );
    }
}
