/*
 * Minecraft Forge
 * Copyright (c) 2016-2020.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.fml.common.asm.transformers.deobf;

import java.io.IOException;
import java.io.InputStream;

import org.tukaani.xz.LZMAInputStream;

import com.google.common.io.ByteSource;

public class LZMAInputSupplier extends ByteSource {
    /**
     * Upper bound on the dictionary an .lzma header may ask for, in KiB. What the build writes declares 8 MiB
     * (commons-compress' default preset - the 1.8 MB binpatch pack needs 8214 KiB), so this leaves four times the
     * headroom and still refuses a tampered header instead of allocating whatever it claims.
     */
    private static final int MEMORY_LIMIT_KB = 32 * 1024;

    private InputStream compressedData;

    public LZMAInputSupplier(InputStream compressedData)
    {
        this.compressedData = compressedData;
    }

    /**
     * Opens {@code compressed} as a decoded .lzma stream. ClassPatchManager reads its pack through this too, so the
     * memory limit is stated once rather than at both call sites.
     */
    public static InputStream open(InputStream compressed) throws IOException
    {
        return new LZMAInputStream(compressed, MEMORY_LIMIT_KB);
    }

    @Override
    public InputStream openStream() throws IOException
    {
        return open(this.compressedData);
    }

}
