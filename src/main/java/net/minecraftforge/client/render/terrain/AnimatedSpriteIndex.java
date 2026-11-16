package net.minecraftforge.client.render.terrain;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.util.List;

/**
 * Which animated sprite a terrain quad samples, by its average texture coordinate.
 *
 * <p><b>The problem.</b> Vanilla ticks every animated sprite on the atlas every frame, and a tick can upload a new
 * frame. A pack with a few hundred animated textures therefore uploads frames for water, lava, fire and every animated
 * machine casing whether or not any of them is on screen. Nothing in the mesh says which sprite a quad came from - the
 * block model renderer bakes absolute atlas coordinates into the vertex and never touches the sprite again.</p>
 *
 * <p><b>Why the average of the quad's four texture coordinates is enough.</b> A baked quad's four UVs lie inside one
 * sprite's rectangle, so their average does too. That turns identification into one bucket lookup and one rectangle
 * test, which is what makes it affordable to do while the mesh is being captured rather than in a second pass over the
 * models.</p>
 *
 * <p><b>Only animated sprites are indexed.</b> Those are the only ones a tick costs anything for, and there are few
 * enough that a coarse grid over texture space holds a handful of candidates per cell.</p>
 */
public final class AnimatedSpriteIndex {

    /** Cells per axis. 64 gives 1/64 of the atlas per cell - coarse, but a sprite is bigger than that in practice. */
    private static final int GRID = 64;
    private static final int CELLS = GRID * GRID;

    /** The sprites, or null before the atlas has been stitched. */
    private static volatile AnimatedSpriteIndex current;

    private final TextureAtlasSprite[] sprites;
    private final float[] minU;
    private final float[] maxU;
    private final float[] minV;
    private final float[] maxV;
    /** For each cell, the sprites that may cover it, as indices into {@link #sprites}. */
    private final int[][] cells;

    private AnimatedSpriteIndex(List<TextureAtlasSprite> animated) {
        int count = animated.size();
        this.sprites = animated.toArray(new TextureAtlasSprite[count]);
        this.minU = new float[count];
        this.maxU = new float[count];
        this.minV = new float[count];
        this.maxV = new float[count];

        // One pass to count, one to fill: the lists are tiny (a few hundred at most) and this avoids a boxed
        // per-cell collection on the atlas-load path.
        int[] counts = new int[CELLS];
        for (int i = 0; i < count; i++) {
            TextureAtlasSprite sprite = this.sprites[i];
            this.minU[i] = sprite.getMinU();
            this.maxU[i] = sprite.getMaxU();
            this.minV[i] = sprite.getMinV();
            this.maxV[i] = sprite.getMaxV();
            int x0 = cellOf(this.minU[i]);
            int x1 = cellOf(this.maxU[i]);
            int y0 = cellOf(this.minV[i]);
            int y1 = cellOf(this.maxV[i]);
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    counts[y * GRID + x]++;
                }
            }
        }

        this.cells = new int[CELLS][];
        for (int i = 0; i < CELLS; i++) {
            this.cells[i] = counts[i] == 0 ? EMPTY : new int[counts[i]];
        }
        int[] filled = new int[CELLS];
        for (int i = 0; i < count; i++) {
            int x0 = cellOf(this.minU[i]);
            int x1 = cellOf(this.maxU[i]);
            int y0 = cellOf(this.minV[i]);
            int y1 = cellOf(this.maxV[i]);
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    int cell = y * GRID + x;
                    this.cells[cell][filled[cell]++] = i;
                }
            }
        }
    }

    private static final int[] EMPTY = new int[0];

    /**
     * Rebuilds the index for a freshly stitched atlas.
     *
     * <p>Called once per resource reload. An empty list still installs an index rather than nulling the field: "this
     * atlas has no animated sprites" is a fact worth having, and a null would read as "not built yet".</p>
     */
    public static void rebuild(List<TextureAtlasSprite> animated) {
        current = new AnimatedSpriteIndex(animated);
    }

    /** The index for the current atlas, or null before it has been stitched. */
    public static AnimatedSpriteIndex current() {
        return current;
    }

    /**
     * The animated sprite whose rectangle contains {@code (u, v)}, or null when the coordinate is not on one.
     */
    public TextureAtlasSprite find(float u, float v) {
        if (u < 0.0F || u > 1.0F || v < 0.0F || v > 1.0F) {
            return null;
        }
        int[] candidates = this.cells[cellOf(v) * GRID + cellOf(u)];
        for (int i = 0; i < candidates.length; i++) {
            int index = candidates[i];
            if (u >= this.minU[index] && u <= this.maxU[index] && v >= this.minV[index] && v <= this.maxV[index]) {
                return this.sprites[index];
            }
        }
        return null;
    }

    private static int cellOf(float coordinate) {
        int cell = (int) (coordinate * GRID);
        return cell < 0 ? 0 : (cell >= GRID ? GRID - 1 : cell);
    }
}
