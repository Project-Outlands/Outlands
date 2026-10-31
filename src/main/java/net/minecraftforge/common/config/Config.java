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

package net.minecraftforge.common.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Config
{
    /**
    * The mod id that this configuration is associated with.
    */
    String modid();
    /**
     * A user friendly name for the config file,
     * the default will be modid
     */
    String name() default "";

    /**
     * The type this is, right now the only value is INSTANCE.
     * This is intended to be expanded upon later for more Forge controlled
     * configs.
     */
    Type type() default Type.INSTANCE;

    /**
     * Root element category, defaults to "general", if this is an empty string then the root category is disabled.
     * Any primitive fields will cause an error, and you must specify sub-category objects
     */
    String category() default "general";

    /**
     * The on-disk syntax of this configuration.
     * <p>
     * Defaults to {@link Format#CFG}, which is the historical format and remains byte-for-byte unchanged. Declaring
     * another format changes the file extension the manager looks for (see {@link ConfigFileResolver}) and asks for a
     * codec that can read and write it.
     * <p>
     * This only has a meaning on the root class of a configuration; nested classes inherit it and the value is not
     * read from them.
     */
    Format format() default Format.CFG;

    /**
     * The {@link Side} this configuration file is registered on.
     * <p>
     * Defaults to {@link Side#BOTH}, meaning the config is registered on both the client and the dedicated server.
     * When a config is marked with a specific side it is only created, loaded and synced on that environment;
     * on the other side it is completely ignored: no config file is created, no fields are read or written
     * and nothing is shown in the config GUI.
     */
    Side side() default Side.BOTH;

    /**
     * The syntaxes a configuration file can be written in.
     * <p>
     * The extension is what identifies the format on disk, so it is part of the enum rather than a separate table.
     * {@link #CFG} comes first because it is the default and therefore wins when several files of the same name exist.
     */
    enum Format
    {
        /** Forge's historical {@code name=value} format, including {@code <} and {@code >} category markers. */
        CFG("cfg"),
        TOML("toml"),
        /** HOCON. {@code .conf} is the extension typesafe-config itself recommends. */
        HOCON("conf"),
        YAML("yml", "yaml"),
        JSON("json");

        private final String[] extensions;

        Format(String... extensions)
        {
            this.extensions = extensions;
        }

        /**
         * The extension written when this format creates a file. Ambiguous formats (YAML) list their preferred
         * spelling first.
         */
        public String extension()
        {
            return extensions[0];
        }

        /** Every spelling this format is recognised by, preferred first. */
        public String[] extensions()
        {
            return extensions.clone();
        }

        /** @return whether {@code extension} (without the dot, any case) is a spelling of this format */
        public boolean matchesExtension(String extension)
        {
            for (String candidate : extensions)
                if (candidate.equalsIgnoreCase(extension))
                    return true;
            return false;
        }

        /**
         * @return the format spelled by {@code extension} (without the dot, any case), or {@code null} if the
         *         extension names no known format
         */
        public static Format byExtension(String extension)
        {
            if (extension == null)
                return null;
            for (Format format : values())
                if (format.matchesExtension(extension))
                    return format;
            return null;
        }
    }

    enum Type
    {
        /**
        * Loaded once, directly after mod construction. Before pre-init.
        * This class must have static fields.
        */
        INSTANCE(true);


        private boolean isStatic = true;
        Type(boolean isStatic) { this.isStatic = isStatic; }
        public boolean isStatic(){ return this.isStatic; }
    }

    /**
     * CLIENT = regular client, SERVER = dedicated server
     */
    enum Side
    {
        BOTH,
        CLIENT,
        SERVER;

        /**
         * Checks whether this config side should be registered in the given environment.
         *
         * @param env the physical side the game is currently running on, or {@code null} if the side could not be determined
         * @return {@code true} if the config should be registered; an unknown environment ({@code null}) is treated as {@link #BOTH}
         */
        public boolean notAppliesTo(net.minecraftforge.fml.relauncher.Side env)
        {
            return env != null && this != BOTH
                && (this != CLIENT || env != net.minecraftforge.fml.relauncher.Side.CLIENT)
                && (this != SERVER || env != net.minecraftforge.fml.relauncher.Side.SERVER);
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.TYPE})
    @interface LangKey
    {
        String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface Comment
    {
        String[] value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface Ignore
    {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface RangeInt
    {
        int min() default Integer.MIN_VALUE;
        int max() default Integer.MAX_VALUE;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface RangeDouble
    {
        double min() default Double.MIN_VALUE;
        double max() default Double.MAX_VALUE;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface Name
    {
        String value();
    }
    
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.TYPE})
    @interface RequiresMcRestart
    {}
    
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.TYPE})
    @interface RequiresWorldRestart
    {}

    /**
     * A field marked with this annotation (and {@link RangeInt} or {@link RangeDouble}) will have a slider control attached in the config UI
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface SlidingOption
    {}

    /**
     * Restricts a single config entry，a whole category or a whole config file to a specific {@link Side}.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.TYPE})
    @interface SideOnly
    {
        Side value();
    }
}
