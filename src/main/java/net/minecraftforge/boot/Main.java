package net.minecraftforge.boot;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import joptsimple.NonOptionArgumentSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;

public class Main {
    static Logger LOGGER = setupLogger();

    private static Logger setupLogger() {
        InputStream is = Main.class.getResourceAsStream("/logging.properties");
        try {
            LogManager.getLogManager().readConfiguration(is);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return Logger.getLogger("LegacyDev");
    }

    protected void start(String[] args) throws ClassNotFoundException, NoSuchMethodException, SecurityException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        String mainClass = getenv("mainClass");
        if (mainClass == null)
            throw new IllegalArgumentException("Must specify mainClass environment variable");
        LOGGER.info("Main Class: " + mainClass);

        String srg2mcp = getenv("MCP_TO_SRG");
        if (srg2mcp != null) {
            LOGGER.info("Srg2Mcp: " + srg2mcp);
            System.setProperty("net.minecraftforge.gradle.GradleStart.srg.srg-mcp", srg2mcp);
        }

        List<String> cleanArgs = parseArgs(args);
        captureAssetPaths(cleanArgs);

        StringBuilder b = new StringBuilder();
        b.append('[');
        int size = cleanArgs.size();
        for (int x = 0; x < size; x++) {
            b.append(cleanArgs.get(x));
            if ("--accessToken".equalsIgnoreCase(cleanArgs.get(x))) {
                b.append(", {REDACTED}");
                x++;
            }

            if (x < size - 1)
                b.append(", ");
        }
        b.append(']');
        LOGGER.info("Running with arguments: " + b);
        Class<?> cls = Class.forName(mainClass, true, getClass().getClassLoader());
        Method main = cls.getDeclaredMethod("main", String[].class);
        main.invoke(null, new Object[] { cleanArgs.toArray(new String[0]) });
    }

    protected Map<String, String> getDefaultArguments() {
        return new LinkedHashMap<>();
    }

    private List<String> parseArgs(String[] args) {
        final Map<String, String> defaults = getDefaultArguments();

        final OptionParser parser = new OptionParser();
        parser.allowsUnrecognizedOptions();

        for (String key : defaults.keySet())
            parser.accepts(key).withRequiredArg().ofType(String.class);

        final NonOptionArgumentSpec<String> nonOption = parser.nonOptions();

        final OptionSet options = parser.parse(args);
        for (String key : defaults.keySet()) {
            if (options.hasArgument(key)) {
                String value = (String) options.valueOf(key);
                defaults.put(key, value);
                if (!"password".equalsIgnoreCase(key) && !"accessToken".equalsIgnoreCase(key))
                    LOGGER.info(key + ": " + value);
            }
        }

        List<String> extras = new ArrayList<>(nonOption.values(options));
        LOGGER.info("Extra: " + extras);

        List<String> lst = new ArrayList<>();
        for (Map.Entry<String, String> entry : defaults.entrySet()) {
            if (!nullOrEmpty(entry.getValue())) {
                lst.add("--" + entry.getKey());
                lst.add(entry.getValue());
            }
        }

        String tweak = getenv("tweakClass");
        if (!nullOrEmpty(tweak)) {
            lst.add("--tweakClass");
            lst.add(tweak);
        }

        lst.addAll(extras);

        return lst;
    }

    /**
     * Keeps the two paths the asset lookup needs, before the game is started: the launcher passes where the downloaded
     * objects live and which index names them, and nothing else in the loader can see either one.
     */
    private static void captureAssetPaths(List<String> args) {
        for (int i = 0; i + 1 < args.size(); i++) {
            final String arg = args.get(i);
            if ("--assetsDir".equals(arg) && System.getProperty("outlands.assets.dir") == null) {
                System.setProperty("outlands.assets.dir", args.get(i + 1));
                LOGGER.info("Assets directory: " + args.get(i + 1));
            } else if ("--assetIndex".equals(arg) && System.getProperty("outlands.assets.index") == null) {
                System.setProperty("outlands.assets.index", args.get(i + 1));
                LOGGER.info("Asset index: " + args.get(i + 1));
            }
        }
    }

    protected String getenv(String name) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? null : value;
    }

    protected boolean nullOrEmpty(String value) {
        return value == null || value.isEmpty();
    }

}
