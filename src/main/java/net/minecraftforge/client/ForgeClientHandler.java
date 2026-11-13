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

package net.minecraftforge.client;

import net.minecraftforge.client.ime.IMEHandler;
import net.minecraftforge.client.ime.TextFieldHandler;
import net.minecraftforge.client.modlist.LegacyModListScreen;
import net.minecraftforge.client.modlist.ModListConfig;
import net.minecraftforge.client.modlist.ModListConstants;
import net.minecraftforge.client.modlist.screen.ModListScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenBook;
import net.minecraft.client.gui.inventory.GuiEditSign;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.translation.I18n;
import net.minecraftforge.client.event.ColorHandlerEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.common.ForgeEarlyConfig;
import net.minecraftforge.common.ForgeModContainer;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fml.client.event.ConfigChangedEvent.OnConfigChangedEvent;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.oredict.OreDictionary;
import org.lwjgl.MainThread;
import org.lwjgl.opengl.Display;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLVideo;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ForgeClientHandler
{
    private static final Set<String> classList = Arrays.stream(ForgeModContainer.inputMethodGuiWhiteList).collect(Collectors.toSet());
    private static boolean played = false;
    @SubscribeEvent
    public static void registerModels(ModelRegistryEvent event)
    {
        // The input method has no way of being reached on screens the game does not recognise as text screens, so
        // the player gets a key for it. Registered here because that is the first point where the client is far
        // enough along for game settings to exist.
        net.minecraftforge.client.ime.ImeForceKey.register();
        // register model for the universal bucket, if it exists
        if (FluidRegistry.isUniversalBucketEnabled())
        {
            ModelLoader.setBucketModelDefinition(ForgeModContainer.getInstance().universalBucket);
        }
    }

    @SubscribeEvent
    public static void registerItemHandlers(ColorHandlerEvent.Item event)
    {
        if (FluidRegistry.isUniversalBucketEnabled())
        {
            event.getItemColors().registerItemColorHandler(new FluidContainerColorer(), ForgeModContainer.getInstance().universalBucket);
        }
    }

    /**
     * Keeps the OS input method window next to whatever is being edited. Driven from the client tick rather than a
     * screen's drawScreen: most screens override drawScreen without calling super, so a draw-side hook silently
     * misses exactly the screens (books, signs, mod screens) that have no text field to report an anchor.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase == TickEvent.Phase.END)
        {
            net.minecraftforge.client.ime.TextInputArea.update(Minecraft.getMinecraft().currentScreen);
        }
    }

    @SubscribeEvent
    public static void onGuiOpen(GuiOpenEvent event)
    {
        GuiScreen gui = event.getGui();
        if (gui != Minecraft.getMinecraft().currentScreen)
        {
            // A closing screen must not leave the IME session open behind it.
            TextFieldHandler.resetTextInput();
        }
        if (gui instanceof GuiChat)
        {
            // Skip, this should be handled by Focus
            return;
        }

        // Forge's mod list may get mixin in late phase, which leads to crash
        // Use an interface to avoid this
        if (ModListConfig.enable && gui instanceof LegacyModListScreen modList)
        {
            event.setGui(new ModListScreen(modList.getParent()));
        }

        IMEHandler.setIME(gui != null &&
                (ForgeEarlyConfig.imeAvailableInGui || gui instanceof GuiScreenBook || gui instanceof GuiEditSign
                        || guiInWhiteList(gui)));
    }

    @SubscribeEvent
    public static void onConfigChanged(OnConfigChangedEvent event)
    {
        if (ModListConstants.OWNER_MOD_ID.equals(event.getModID()))
        {
            ConfigManager.sync(ModListConfig.class);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onGuiOpenLast(GuiOpenEvent event)
    {
        // The window is the only thing that can still be missing here: the main menu is reached long after the
        // window exists, but a shutdown in progress clears it. Asking Display instead of caching a handle keeps
        // this from flashing a window that has already been destroyed.
        if (event.getGui() instanceof GuiMainMenu && !played && Display.isCreated())
        {
            if (!ForgeEarlyConfig.modernWindowsStyles.disableFlashAfterLoaded)
            {
                // SDL maps this onto each desktop's own attention request: FlashWindowEx on Windows, an
                // urgency hint on X11, the activation protocol on Wayland, requestUserAttention on macOS.
                // A desktop that cannot do it is not a reason to fail the launch: the flash is cosmetic.
                // Window calls belong to the first thread, so the request is marshalled there.
                final long window = Display.getWindow();
                MainThread.runOnMainThread(() ->
                {
                    if (!SDLVideo.SDL_FlashWindow(window, SDLVideo.SDL_FLASH_UNTIL_FOCUSED))
                    {
                        FMLLog.log.debug("Taskbar flash not supported by this desktop ({}), continuing without it",
                            SDLError.SDL_GetError());
                    }
                });
            }
            played = true;
        }
    }

    private static boolean guiInWhiteList(GuiScreen gui) {
        String current = gui.getClass().getName();
        return classList.contains(current);
    }
    
    @SubscribeEvent
    public static void appendAdvancedTooltip(ItemTooltipEvent event) {
        if (!ForgeModContainer.displayAdvancedTooltips || !event.getFlags().isAdvanced())
        {
            return;
        }
        List<String> list = event.getToolTip();
        ItemStack stack = event.getItemStack();
        
        int limit = ForgeModContainer.maxTooltipNBTListLength;
        list.add(I18n.translateToLocal("item.forge.advanced_shift_info"));
        if (stack.hasTagCompound() && limit > 0) list.add(I18n.translateToLocal("item.forge.advanced_ctrl_info"));
        String pre = TextFormatting.DARK_GRAY + "     ";
        String head = TextFormatting.GRAY + "  -";
        
        int burnTime = TileEntityFurnace.getItemBurnTime(stack);
        if (burnTime > 0)
        {
            list.add(TextFormatting.DARK_GRAY + I18n.translateToLocalFormatted("item.burn_time", burnTime, burnTime / 20));
        }
        
        if (GuiScreen.isShiftKeyDown() && !stack.isEmpty())
        {
            list.add(TextFormatting.DARK_GRAY + "" + TextFormatting.ITALIC + I18n.translateToLocal("item.forge.advanced_info"));

            int[] ids = OreDictionary.getOreIDs(stack);
            if (ids.length > 0)
            {
                list.add(head + I18n.translateToLocal("item.forge.oredict"));
                for (int id : ids)
                {
                    list.add(pre + OreDictionary.getOreName(id));
                }
            }

            String baseName = stack.getTranslationKey();
            if (!baseName.equals("item.null"))
            {
                list.add(head + I18n.translateToLocal("item.forge.translation_key"));
                list.add(pre + baseName);
            }
        }

        if (GuiScreen.isCtrlKeyDown() && limit > 0 && !stack.isEmpty())
        {
            NBTTagCompound compound = stack.getTagCompound();
            if (compound != null && !compound.isEmpty())
            {
                list.add(head + I18n.translateToLocal("item.forge.nbt_list"));

                String compoundStr = compound.toString();
                int compoundStrLength = compoundStr.length();
                String compoundDisplay;

                if (compoundStrLength > limit)
                {
                    compoundDisplay = compoundStr.substring(0, limit) + TextFormatting.GRAY + " " + I18n.translateToLocalFormatted("item.forge.nbt_limited", limit);
                }
                else
                {
                    compoundDisplay = compoundStr;
                }
                list.add(pre + compoundDisplay);
            }
        }
    }
}
