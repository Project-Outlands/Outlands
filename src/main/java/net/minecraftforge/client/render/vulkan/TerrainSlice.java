package net.minecraftforge.client.render.vulkan;

/**
 * Metadata for a chunk layer's slice within an arena slab.
 */
public final class TerrainSlice {

    private final int slabIndex;
    private final int byteOffset;
    private final int vertexOffset;
    private final int quadCount;
    private final int indexCount;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final int layer;
    private final TerrainArenaPool.Allocation allocation;
    int layerListIndex;

    public TerrainSlice(int slabIndex, int byteOffset, int vertexOffset, int quadCount, int indexCount,
                        int originX, int originY, int originZ, int layer,
                        TerrainArenaPool.Allocation allocation) {
        this.slabIndex = slabIndex;
        this.byteOffset = byteOffset;
        this.vertexOffset = vertexOffset;
        this.quadCount = quadCount;
        this.indexCount = indexCount;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.layer = layer;
        this.allocation = allocation;
        this.layerListIndex = -1;
    }

    public int slabIndex() {
        return slabIndex;
    }

    public int byteOffset() {
        return byteOffset;
    }

    public int vertexOffset() {
        return vertexOffset;
    }

    public int quadCount() {
        return quadCount;
    }

    public int indexCount() {
        return indexCount;
    }

    public int originX() {
        return originX;
    }

    public int originY() {
        return originY;
    }

    public int originZ() {
        return originZ;
    }

    public int layer() {
        return layer;
    }

    public TerrainArenaPool.Allocation allocation() {
        return allocation;
    }

    public int layerListIndex() {
        return layerListIndex;
    }

    public void setLayerListIndex(int layerListIndex) {
        this.layerListIndex = layerListIndex;
    }
}
