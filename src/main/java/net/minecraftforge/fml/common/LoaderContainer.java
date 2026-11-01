package net.minecraftforge.fml.common;

import com.google.common.eventbus.EventBus;
import net.minecraftforge.common.LoaderVersion;
import net.minecraftforge.fml.common.DummyModContainer;
import net.minecraftforge.fml.common.LoadController;
import net.minecraftforge.fml.common.ModMetadata;

import java.net.URL;
import java.util.Arrays;

public class LoaderContainer extends DummyModContainer {
    public LoaderContainer() {
        super(new ModMetadata());
        ModMetadata meta = this.getMetadata();
        meta.modId = "outlands";
        meta.name = "Outlands";
        meta.description = """
                Outlands is a 1.12.2 Forge fork, providing newer toolchain, new APIs and 99% compatibility.
                Our plan is to make 1.12.2 up-to-date with latest toolchain while adding more feature to Forge & vanilla, so we don't need to care about higher versions' X point release * Y Mod API headache.
                """;
        meta.version = LoaderVersion.VERSION;
        meta.authorList = Arrays.asList("LexManos", "cpw", "fry", "Rongmario", "kappa_maintainer", "Li");
        meta.credits = "MrCrayFish for the Catalogue mod as base for the revamped mod list.";
        meta.url = "https://github.com/Project-Outlands/Outlands";
        meta.modProperties.put("issueTrackerUrl", "https://github.com/Project-Outlands/Outlands/issues");
        meta.logoFile = "/assets/outlands/banner.png";
        meta.modProperties.put("iconFile", "/assets/outlands/icon.png");
        meta.modProperties.put("backgroundFile", "/assets/outlands/background.png");
    }

    @Override
    public boolean registerBus(EventBus bus, LoadController controller) {
        return true;
    }
    
    /**
     * No update source on purpose. The endpoint this used to point at belongs to another project, so the only thing a
     * check could report is a version of something we are not. When there is an Outlands endpoint, set updateJSON again
     * and delete this override.
     */
    @Override
    public URL getUpdateUrl() {
        return null;
    }
}
