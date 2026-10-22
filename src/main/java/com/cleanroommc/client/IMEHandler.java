package com.cleanroommc.client;

import com.cleanroommc.client.ime.DummyIMEHandler;
import com.cleanroommc.client.ime.SdlTextInputHandler;
import com.cleanroommc.client.ime.WindowsIMEHandler;
import net.minecraftforge.common.ForgeModContainer;
import net.minecraftforge.fml.common.FMLLog;
import org.lwjgl.sdl.SDLVideo;

import java.util.function.Consumer;

public class IMEHandler {
    private static final Consumer<Boolean> instance;
    static {
        if (ForgeModContainer.inputMethodBlockingEnabled) {
            final String videoDriver = SDLVideo.SDL_GetCurrentVideoDriver();
            if (videoDriver == null) {
                instance = new DummyIMEHandler();
                FMLLog.log.warn("IME handler initialization failed: SDL video driver unavailable");
            } else {
                switch (videoDriver) {
                    case "windows" -> instance = new WindowsIMEHandler();
                    case "x11", "wayland" -> instance = new SdlTextInputHandler();
                    default -> {
                        instance = new DummyIMEHandler();
                        FMLLog.log.warn(
                            "IME handler initialization failed: unsupported SDL video driver {}",
                            videoDriver);
                    }
                }
            }
        } else {
            instance = new DummyIMEHandler();
        }
    }
    public static void setIME(boolean active) {
        instance.accept(active);
    }
}
