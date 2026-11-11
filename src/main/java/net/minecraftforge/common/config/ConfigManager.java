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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

import net.minecraftforge.fml.relauncher.FMLLaunchHandler;
import net.minecraftforge.fml.relauncher.FMLInjectionData;
import net.minecraftforge.common.config.Config.Comment;
import net.minecraftforge.common.config.Config.LangKey;
import net.minecraftforge.common.config.Config.Name;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.LoaderException;
import net.minecraftforge.fml.common.LoaderState;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.common.discovery.ASMDataTable;
import net.minecraftforge.fml.common.discovery.ASMDataTable.ASMData;
import net.minecraftforge.fml.common.discovery.asm.ModAnnotation.EnumHolder;

import org.apache.commons.lang3.StringUtils;
import top.outlands.foundation.boot.TransformerHolder;

public class ConfigManager
{
    private static final Map<String, Multimap<Config.Type, ASMData>> asm_data = Maps.newHashMap();
    static Map<Class<?>, ITypeAdapter> ADAPTERS = Maps.newHashMap();
    static Map<Class<?>, Class<?>> ARRAY_REMAP = Maps.newHashMap();
    private static final Map<String, Configuration> CONFIGS = Maps.newHashMap();
    private static final Map<String, Set<Class<?>>> MOD_CONFIG_CLASSES = Maps.newHashMap();
    private static final Map<Class<?>, Configuration> CLASS_TO_CONFIG = Maps.newHashMap();
    /** Absolute paths already mentioned in a startup log line, so a file is only explained once. */
    private static final Set<String> WARNED_RESOLUTIONS = Sets.newHashSet();
    private static ConfigEnvironment environment;
    private static final ClassValue<Field[]> PUBLIC_FIELDS = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            return Arrays.stream(type.getDeclaredFields())
                    .filter(field -> Modifier.isPublic(field.getModifiers())).toArray(Field[]::new);
        }
    };

    static
    {
        register(boolean.class,     TypeAdapters.bool);
        register(boolean[].class,   TypeAdapters.boolA);
        register(Boolean.class,     TypeAdapters.Bool);
        register(Boolean[].class,   TypeAdapters.BoolA);
        register(float.class,       TypeAdapters.flt);
        register(float[].class,     TypeAdapters.fltA);
        register(Float.class,       TypeAdapters.Flt);
        register(Float[].class,     TypeAdapters.FltA);
        register(double.class,      TypeAdapters.dbl);
        register(double[].class,    TypeAdapters.dblA);
        register(Double.class,      TypeAdapters.Dbl);
        register(Double[].class,    TypeAdapters.DblA);
        register(byte.class,        TypeAdapters.byt);
        register(byte[].class,      TypeAdapters.bytA);
        register(Byte.class,        TypeAdapters.Byt);
        register(Byte[].class,      TypeAdapters.BytA);
        register(char.class,        TypeAdapters.chr);
        register(char[].class,      TypeAdapters.chrA);
        register(Character.class,   TypeAdapters.Chr);
        register(Character[].class, TypeAdapters.ChrA);
        register(short.class,       TypeAdapters.shrt);
        register(short[].class,     TypeAdapters.shrtA);
        register(Short.class,       TypeAdapters.Shrt);
        register(Short[].class,     TypeAdapters.ShrtA);
        register(int.class,         TypeAdapters.int_);
        register(int[].class,       TypeAdapters.intA);
        register(Integer.class,     TypeAdapters.Int);
        register(Integer[].class,   TypeAdapters.IntA);
        register(String.class,      TypeAdapters.Str);
        register(String[].class,    TypeAdapters.StrA);


        ARRAY_REMAP.put(Boolean.class,   Boolean[].class  );
        ARRAY_REMAP.put(Float.class,     Float[].class    );
        ARRAY_REMAP.put(Double.class,    Double[].class   );
        ARRAY_REMAP.put(Byte.class,      Byte[].class     );
        ARRAY_REMAP.put(Character.class, Character[].class);
        ARRAY_REMAP.put(Short.class,     Short[].class    );
        ARRAY_REMAP.put(Integer.class,   Integer[].class  );
        ARRAY_REMAP.put(String.class,    String[].class   );
    }
    private static void register(Class<?> cls, ITypeAdapter adpt)
    {
        ADAPTERS.put(cls, adpt);
    }

    public static void loadData(ASMDataTable data)
    {
        FMLLog.log.debug("Loading @Config annotation data");
        Side currentSide = currentSide();
        for (ASMData target : data.getAll(Config.class))
        {
            String modid = (String)target.getAnnotationInfo().get("modid");
            Multimap<Config.Type, ASMData> map = asm_data.computeIfAbsent(modid, k -> ArrayListMultimap.create());

            EnumHolder tholder = (EnumHolder)target.getAnnotationInfo().get("type");
            Config.Type type = tholder == null ? Config.Type.INSTANCE : Config.Type.valueOf(tholder.getValue());

            EnumHolder sideHolder = (EnumHolder)target.getAnnotationInfo().get("side");
            Config.Side side = sideHolder == null ? Config.Side.BOTH : Config.Side.valueOf(sideHolder.getValue());
            if (side.notAppliesTo(currentSide))
            {
                FMLLog.log.debug("Skipping @Config class {} on side {}: marked as {}", target.getClassName(), currentSide, side);
                continue;
            }

            map.put(type, target);
        }
    }

    /**
     * Bounces to sync().
     * TODO: remove
     */
    public static void load(String modid, Config.Type type)
    {
        sync(modid, type);
    }

    /**
     * Synchronizes configuration data between the file on disk, the {@code Configuration} object and the annotated
     * mod classes containing the configuration variables.
     *
     * When first called, this method will try to load the configuration from disk. If this fails, because the file
     * does not exist, it will be created with default values derived from the mods config classes variable default values
     * and comments and ranges, as well as configuration names based on the appropriate annotations found in {@code @Config}.
     *
     * Note, that this method is being called by the {@link net.minecraftforge.fml.common.FMLModContainer}, so the mod needn't call it in init().
     *
     * If this method is called after the initial load, it will check whether the values in the Configuration object differ
     * from the values in the corresponding variables. If they differ, it will either overwrite the variables if the Configuration
     * object is marked as changed (e.g. if it was changed with the ConfigGui) or otherwise overwrite the Configuration object's values.
     * It then proceeds to saving the changes to disk.
     * @param modid the mod's ID for which the configuration shall be loaded
     * @param type the configuration type, currently always {@code Config.Type.INSTANCE}
     */
    public static void sync(String modid, Config.Type type)
    {
        FMLLog.log.debug("Attempting to inject @Config classes into {} for type {}", modid, type);
        ClassLoader mcl = environment().modClassLoader();
        Multimap<Config.Type, ASMData> map = asm_data.get(modid);

        if (map == null)
            return;

        Side currentSide = currentSide();
        Set<Configuration> touched = Sets.newHashSet();
        for (ASMData targ : map.get(type))
        {
            try
            {
                Class<?> cls = Class.forName(targ.getClassName(), true, mcl);

                Config config = cls.getAnnotation(Config.class);
                if (config != null && config.side().notAppliesTo(currentSide))
                {
                    FMLLog.log.debug("Skipping @Config class {} on side {}: marked as {}", targ.getClassName(), currentSide, config.side());
                    continue;
                }

                Set<Class<?>> modConfigClasses = MOD_CONFIG_CLASSES.computeIfAbsent(modid, k -> Sets.<Class<?>>newHashSet());
                modConfigClasses.add(cls);

                String name = (String)targ.getAnnotationInfo().get("name");
                if (name == null)
                    name = modid;
                String category = (String)targ.getAnnotationInfo().get("category");
                if (category == null)
                    category = "general";

                Config.Format declared = config != null ? config.format() : null;
                Config.Storage storage = config != null ? config.storage() : Config.Storage.INSTANCE;
                Configuration cfg = configuration(ConfigFileResolver.resolve(rootFor(storage), modid, name, declared));
                sync(cfg, cls, modid, category, environment().loading(), null);

                cfg.save();
                touched.add(cfg);

            }
            catch (Exception e)
            {
                FMLLog.log.error("An error occurred trying to load a config for {} into {}", modid, targ.getClassName(), e);
                throw new LoaderException(e);
            }
        }

        // Only now can a key be called unknown: every class of this mod has had its chance to declare it.
        for (Configuration cfg : touched)
            ConfigPatch.reportUnused(cfg);
    }

    /**
     * Tells the config subsystem what it is running inside. The loader does this once it knows its directories; until
     * then a loader-backed default is used so that early calls behave exactly as before.
     */
    public static void setEnvironment(ConfigEnvironment env)
    {
        environment = env;
    }

    static ConfigEnvironment environment()
    {
        ConfigEnvironment env = environment;
        if (env != null)
            return env;

        if (fallback == null)
            fallback = new LoaderEnvironment();
        return fallback;
    }

    private static ConfigEnvironment fallback;

    /** The pre-injection behaviour, kept in one place instead of at every call site. */
    private static final class LoaderEnvironment implements ConfigEnvironment
    {
        @Override
        public File configDir()
        {
            if (TransformerHolder.renameTransformer != null)
            {
                File dir = Loader.instance().getConfigDir();
                if (dir != null)
                    return dir;
            }

            // The early config classes are registered before Loader has resolved its directories. Falling back to the
            // launch home keeps those files under <instance>/config instead of the process working directory.
            Object home = FMLInjectionData.data()[6];
            if (home instanceof File file)
            {
                try
                {
                    return new File(file, "config").getCanonicalFile();
                }
                catch (IOException ignored)
                {
                    return new File(file, "config").getAbsoluteFile();
                }
            }
            return null;
        }

        @Override
        public File userConfigDir()
        {
            return findUserConfigDir();
        }

        @Override
        public ClassLoader modClassLoader()
        {
            return Loader.instance().getModClassLoader();
        }

        @Override
        public boolean loading()
        {
            return !Loader.instance().hasReachedState(LoaderState.AVAILABLE);
        }

        @Override
        public Side side()
        {
            return currentSide();
        }
    }

    /**
     * The platform's per-user directory for configuration, or {@code null} when it offers none.
     *
     * <p>{@code -Doutlands.userConfigDir=...} wins over everything else. It is what lets a pack, a test or a server
     * operator point the file somewhere writable without the mod author having to guess, and it is the only way to pin
     * this down in a build that runs on a container with no home directory.
     */
    static File findUserConfigDir()
    {
        String override = System.getProperty("outlands.userConfigDir");
        if (override != null && !override.isEmpty())
            return new File(override);

        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        if (os.startsWith("windows"))
        {
            String appData = System.getenv("APPDATA");
            return appData == null || appData.isEmpty() ? null : new File(appData);
        }

        if (os.startsWith("mac") || os.contains("darwin"))
            return home == null || home.isEmpty() ? null : new File(new File(home), "Library/Application Support");

        String xdg = System.getenv("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isEmpty())
            return new File(xdg);

        return home == null || home.isEmpty() ? null : new File(home, ".config");
    }

    public static Class<?>[] getModConfigClasses(String modid)
    {
        return (MOD_CONFIG_CLASSES.containsKey(modid) ? MOD_CONFIG_CLASSES.get(modid).toArray(new Class<?>[0]) : new Class<?>[0]);
    }

    public static boolean hasConfigForMod(String modid)
    {
        return asm_data.containsKey(modid) || MOD_CONFIG_CLASSES.containsKey(modid);
    }

    // =======================================================
    //                    INTERNAL
    // =======================================================
    static Configuration getConfiguration(Config annotation) {
        return configuration(resolve(annotation));
    }

    /** Resolves one declaration against the root its {@link Config.Storage} names. */
    private static ConfigFileResolver.Target resolve(Config annotation) {
        ConfigFileResolver.Target target = ConfigFileResolver.resolve(rootFor(annotation.storage()), annotation);
        if (annotation.storage() == Config.Storage.USER)
            warnAboutShadowedInstanceFile(target);
        return target;
    }

    /**
     * A config that moved to {@link Config.Storage#USER} leaves the old {@code config/} file behind, and to the player
     * that looks like the mod reset their settings. Nothing is copied or deleted - the two files may hold values for
     * different mod versions - but silently ignoring a file the user has been editing is not an option either.
     */
    private static void warnAboutShadowedInstanceFile(ConfigFileResolver.Target target) {
        File userDir = environment().userConfigDir();
        if (userDir == null)
            return; // fell back to config/, so there is nothing else to look at

        Path userRoot = userDir.getAbsoluteFile().toPath().normalize();
        Path file = target.file().getAbsoluteFile().toPath().normalize();
        if (file.equals(userRoot) || !file.startsWith(userRoot))
            return;

        File shadowed = new File(environment().configDir(), userRoot.relativize(file).toString());
        if (shadowed.isFile() && WARNED_RESOLUTIONS.add("shadowed:" + shadowed.getAbsolutePath()))
            FMLLog.log.warn("{} is declared user-level, so {} is not read. Copy the values you want to keep and delete it.",
                    target.file(), shadowed);
    }

    /**
     * Maps {@link Config.Storage} to the directory the file search happens in.
     *
     * <p>A platform with no per-user directory falls back to {@code config/} and says so once. Failing the load would
     * take the game down over a directory that was only ever a convenience, and silently using {@code config/} would
     * hide why the file is not where the author promised.
     */
    private static File rootFor(Config.Storage storage) {
        if (storage != Config.Storage.USER)
            return environment().configDir();

        File userDir = environment().userConfigDir();
        if (userDir != null)
            return userDir;

        if (WARNED_RESOLUTIONS.add("no-user-config-dir"))
            FMLLog.log.warn("A @Config asks for a file in the user config directory, but this platform has none."
                    + " Using {} instead.", environment().configDir());
        return environment().configDir();
    }

    /**
     * Hands back the {@link Configuration} behind one resolved file, loading it the first time it is asked for.
     *
     * <p>This is the only place a {@link Configuration} is created for an {@code @Config} class, so it is also the only
     * place that has to know which syntaxes can actually be read. Declaring a format nobody can parse yet has to fail
     * here rather than quietly produce a CFG file under a {@code .toml} name, because that would look like it worked.
     */
    private static Configuration configuration(ConfigFileResolver.Target target)
    {
        warnAboutResolution(target);

        String key = key(target.file());
        Configuration cfg = CONFIGS.get(key);
        if (cfg == null)
        {
            cfg = new Configuration(target.file(), target.format());
            cfg.load();
            cfg.setPatchLayer(ConfigPatch.load(environment().configDir(), target.file()));
            CONFIGS.put(key, cfg);
        }
        return cfg;
    }

    /**
     * The cache key for one config file. Canonical when possible: the loader hands out canonical directories, while an
     * early registration may still hold the launch path ("<home>/config" vs "<home>/./config"), and the two spellings
     * must not become two {@link Configuration} objects for one file - their saves would overwrite each other.
     */
    private static String key(File file)
    {
        try
        {
            return file.getCanonicalPath();
        }
        catch (IOException e)
        {
            return file.getAbsolutePath();
        }
    }

    private static void warnAboutResolution(ConfigFileResolver.Target target)
    {
        if (!WARNED_RESOLUTIONS.add(target.file().getAbsolutePath()))
            return;

        switch (target.origin())
        {
            case EXISTING_FILE:
                FMLLog.log.warn("Using {} for the config file {} because the @Config annotation names no format."
                        + " Declare the format to make this independent of the files on disk.",
                        target.format(), target.file().getName());
                break;
            case LEGACY_NAME:
                FMLLog.log.warn("The config file {} is named with its extension, which dates from before formats existed."
                        + " Drop the extension from @Config(name) to be able to change the format later.",
                        target.file().getName());
                break;
            default:
                return;
        }

        for (File ignored : target.ignored())
            FMLLog.log.warn("Ignoring {} next to {}, which has the same name but another extension",
                    ignored.getName(), target.file().getName());
    }
    
    private static Side currentSide()
    {
        try
        {
            return FMLLaunchHandler.side();
        }
        catch (Throwable ignored)
        {
            //side not set yet
        }
        return null;
    }

    private static void sync(Configuration cfg, Class<?> cls, String modid, String category, boolean loading, Object instance)
    {
        Side currentSide = currentSide();
        String languagePrefix = modid + "." + (category.isEmpty() ? "" : category + Configuration.CATEGORY_SPLITTER);
        for (Field f : PUBLIC_FIELDS.get(cls))
        {
            //Only the root class may have static fields. Otherwise category tree nodes of the same type would share the
            //contained value messing up the sync
            if (Modifier.isStatic(f.getModifiers()) != (instance == null))
                continue;

            if (f.isAnnotationPresent(Config.Ignore.class))
                continue;

            Config.SideOnly sideOnly = f.getAnnotation(Config.SideOnly.class);
            if (sideOnly == null)
                sideOnly = f.getType().getAnnotation(Config.SideOnly.class);
            if (sideOnly != null && sideOnly.value().notAppliesTo(currentSide))
            {
                FMLLog.log.debug("Skipping config entry '{}' of class '{}' on side {}: marked as {}", f.getName(), cls.getName(), currentSide, sideOnly.value());
                continue;
            }

            boolean wrapped = FieldWrapper.hasWrapperFor(f);
            String comment = null;
            String langKey = null;
            boolean requiresMcRestart = false;
            boolean requiresWorldRestart = false;
            boolean hasSlidingControl = false;
            // Value-only syncs do not consume property presentation metadata. Category nodes still do.
            if (loading || !wrapped)
            {
                Comment ca = f.getAnnotation(Comment.class);
                if (ca != null)
                    comment = NEW_LINE.join(ca.value());
                LangKey la = f.getAnnotation(LangKey.class);
                langKey = la == null ? languagePrefix + f.getName().toLowerCase(Locale.ENGLISH) : la.value();
                requiresMcRestart = f.isAnnotationPresent(Config.RequiresMcRestart.class);
                requiresWorldRestart = f.isAnnotationPresent(Config.RequiresWorldRestart.class);
                hasSlidingControl = f.isAnnotationPresent(Config.SlidingOption.class);
            }

            if (wrapped) //Wrappers exist for primitives, enums, maps and arrays
            {
                if (Strings.isNullOrEmpty(category))
                    throw new RuntimeException("An empty category may not contain anything but objects representing categories!");
                try
                {
                    IFieldWrapper wrapper = FieldWrapper.get(instance, f, category);
                    ITypeAdapter adapt = wrapper.getTypeAdapter();
                    Property.Type propType = adapt.getType();

                    for (String key : wrapper.getKeys()) //Iterate the fully qualified property names the field provides
                    {
                        String suffix = StringUtils.replaceOnce(key, wrapper.getCategory() + Configuration.CATEGORY_SPLITTER, "");

                        boolean existed = exists(cfg, wrapper.getCategory(), suffix);
                        if (!existed || loading) //Creates keys in category specified by the wrapper if new ones are programaticaly added
                        {
                            Property property = property(cfg, wrapper.getCategory(), suffix, propType, adapt.isArrayAdapter());
                            adapt.setDefaultValue(property, wrapper.getValue(key));
                            if (!existed)
                                adapt.setValue(property, wrapper.getValue(key));

                            // After the value exists, never before: a key that the file does not have yet has no value
                            // to keep, and the patch must not become that value.
                            ConfigPatch.applyTo(cfg, wrapper.getCategory(), property);
                            wrapper.setValue(key, adapt.getValue(property));
                        }
                        else //If the key is not new, sync according to shouldReadFromVar()
                        {
                            Property property = property(cfg, wrapper.getCategory(), suffix, propType, adapt.isArrayAdapter());
                            ConfigPatch.applyTo(cfg, wrapper.getCategory(), property);
                            Object propVal = adapt.getValue(property);
                            Object mapVal = wrapper.getValue(key);
                            if (shouldReadFromVar(property, propVal, mapVal))
                                adapt.setValue(property, mapVal);
                            else
                                wrapper.setValue(key, propVal);
                        }
                    }


                    ConfigCategory confCat = cfg.getCategory(wrapper.getCategory());

                    for (Property property : confCat.getOrderedValues()) //Iterate the properties to check for new data from the config side
                    {
                        String key = confCat.getQualifiedName() + Configuration.CATEGORY_SPLITTER + property.getName();
                        if (!wrapper.handlesKey(key))
                            continue;

                        if (loading || !wrapper.hasKey(key))
                        {
                            Object value = wrapper.getTypeAdapter().getValue(property);
                            wrapper.setValue(key, value);
                        }
                    }

                    if (loading)
                        wrapper.setupConfiguration(cfg, comment, langKey, requiresMcRestart, requiresWorldRestart, hasSlidingControl);

                }
                catch (Exception e)
                {
                    String format = "Error syncing field '%s' of class '%s'!";
                    String error = String.format(format, f.getName(), cls.getName());
                    throw new RuntimeException(error, e);
                }
            }
            else if (f.getType().getSuperclass() != null && f.getType().getSuperclass().equals(Object.class))
            { //If the field extends Object directly, descend the object tree and access the objects members
                Object newInstance = null;
                try
                {
                    newInstance = f.get(instance);
                }
                catch (IllegalAccessException e)
                {
                    throw new RuntimeException(e);
                }

                //Setup the sub category with its respective name, comment, language key, etc.
                String sub = (category.isEmpty() ? "" : category + Configuration.CATEGORY_SPLITTER) + getName(f).toLowerCase(Locale.ENGLISH);
                ConfigCategory confCat = cfg.getCategory(sub);
                confCat.setComment(comment);
                confCat.setLanguageKey(langKey);
                confCat.setRequiresMcRestart(requiresMcRestart);
                confCat.setRequiresWorldRestart(requiresWorldRestart);

                sync(cfg, f.getType(), modid, sub, loading, newInstance);
            }
            else
            {
                String format = "Can't handle field '%s' of class '%s': Unknown type.";
                String error = String.format(format, f.getName(), cls.getCanonicalName());
                throw new RuntimeException(error);
            }
        }
    }

    static final Joiner NEW_LINE = Joiner.on('\n');
    static final Joiner PIPE = Joiner.on('|');

    private static Property property(Configuration cfg, String category, String property, Property.Type type, boolean isList)
    {
        Property prop = cfg.getCategory(category).get(property);
        if (prop == null)
        {
            if (isList)
                prop = new Property(property, new String[0], type);
            else
                prop = new Property(property, (String)null, type);
            cfg.getCategory(category).put(property, prop);
        }
        return prop;
    }

    private static boolean exists(Configuration cfg, String category, String property)
    {
        return cfg.hasCategory(category) && cfg.getCategory(category).containsKey(property);
    }

    private static boolean shouldReadFromVar(Property property, Object propValue, Object fieldValue)
    {
        if (!propValue.equals(fieldValue))
        {
            return !property.hasChanged();
        }
        return false;
    }

    private static String getName(Field f)
    {
        if (f.isAnnotationPresent(Name.class))
            return f.getAnnotation(Name.class).value();
        return f.getName();
    }

    /**
     * Register configuration class that is annotated with {@link Config} here for it to be processed immediately with saving and loading supported.
     * Preferably call this method in a static init block at the very end of your configuration class.
     * @param configClass configuration class that is annotated with {@link Config}
     */
    public static void register(Class<?> configClass) {
        Config config = configClass.getAnnotation(Config.class);
        if (config.side().notAppliesTo(currentSide())) {
            FMLLog.log.debug("Skipping @Config class {} on side {}: marked as {}", configClass.getName(), currentSide(), config.side());
            return;
        }
        String modId = config.modid();
        Set<Class<?>> modConfigClasses = MOD_CONFIG_CLASSES.computeIfAbsent(modId, k -> Sets.newHashSet());
        modConfigClasses.add(configClass);
        Configuration cfg = configuration(resolve(config));
        sync(cfg, configClass, modId, config.category(), true, null);
        cfg.save();
        
        CLASS_TO_CONFIG.put(configClass, cfg);
    }

    /**
     * Write changed config value in the parameter configClass to the config file.
     * Must be called after {@link ConfigManager#register(Class)}
     * @param configClass configuration class that is annotated with {@link Config}
     */
    public static void sync(Class<?> configClass) {
        Config config = configClass.getAnnotation(Config.class);
        String modId = config.modid();
        Configuration cfg = CLASS_TO_CONFIG.get(configClass);
        sync(cfg, configClass, modId, config.category(), false, null);
        cfg.save();
    }
    
}
