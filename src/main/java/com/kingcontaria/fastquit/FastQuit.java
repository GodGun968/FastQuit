package com.kingcontaria.fastquit;

import com.kingcontaria.fastquit.mixin.MinecraftClientAccessor;
import com.kingcontaria.fastquit.mixin.MinecraftServerAccessor;
import com.kingcontaria.fastquit.mixin.SessionAccessor;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.Text;
import net.minecraft.world.level.storage.LevelStorage;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class FastQuit implements ClientModInitializer {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final File CONFIG = FabricLoader.getInstance().getConfigDir().resolve("fastquit-config.txt").toFile();
    private static final ModMetadata FASTQUIT = FabricLoader.getInstance().getModContainer("fastquit").orElseThrow().getMetadata();
    private static final String LOG_PREFIX = "[" + FASTQUIT.getName() + "] ";

    /**
     * Map containing all currently saving {@link IntegratedServer}'s, with a {@link Boolean} indicating if the world has been deleted.
     */
    public static final Map<IntegratedServer, WorldInfo> savingWorlds = Collections.synchronizedMap(new HashMap<>());
    /**
     * Stores {@link net.minecraft.world.level.storage.LevelStorage.Session}'s used by FastQuit as to only close them if no other process is currently using them.
     * <p>
     * Needs to be synchronized separately!
     */
    public static final List<LevelStorage.Session> occupiedSessions = new ArrayList<>();

    /**
     * Determines whether a toast gets shown when a world finishes saving.
     */
    public static boolean showToasts = true;
    /**
     * Determines whether the "Saving world" screen gets rendered.
     */
    public static boolean renderSavingScreen = false;
    /**
     * Determines the Thread priority used for {@link IntegratedServer}'s saving in the background.
     * Value needs to be between 0 and 10, with Thread priority staying unchanged if the value is 0.
     */
    public static int backgroundPriority = 2;

    /**
     * Logs the given message.
     */
    public static void log(String msg) {
        LOGGER.info(LOG_PREFIX + msg);
    }

    /**
     * Logs the given warning.
     */
    public static void warn(String msg) {
        LOGGER.warn(LOG_PREFIX + msg);
    }

    /**
     * Logs the given message and error.
     */
    public static void error(String msg, Throwable throwable) {
        LOGGER.error(LOG_PREFIX + msg, throwable);
    }

    @Override
    public void onInitializeClient() {
        if (CONFIG.isFile()) {
            boolean update = readConfig("read");

            if (update) {
                writeConfig("update");
            }
        } else {
            writeConfig("create");
        }

        log("Initialized");
    }

    /**
     * Writes the options to the config file.
     */
    public static void writeConfig(String action) {
        List<String> lines = new ArrayList<>();

        lines.add("# FastQuit Config");
        lines.add("version:" + FASTQUIT.getVersion().getFriendlyString());
        lines.add("");
        lines.add("## Determines whether a toast gets shown when a world finishes saving");
        lines.add("showToasts:" + showToasts);
        lines.add("");
        lines.add("## When playing on high render distance, quitting the world can still take a bit because the client-side chunk storage has to be cleared.");
        lines.add("## By enabling this setting the 'Saving world' screen will be rendered.");
        lines.add("renderSavingScreen:" + renderSavingScreen);
        lines.add("");
        lines.add("## Sets the thread priority of the server when saving worlds in the background");
        lines.add("## This is done to improve client performance while saving, but will make the saving take longer over all");
        lines.add("## Value has to be between 0 and 10, setting it to 0 will disable changing thread priority");
        lines.add("backgroundPriority:" + backgroundPriority);

        try {
            Files.writeString(CONFIG.toPath(), String.join(System.lineSeparator(), lines));
        } catch (IOException e) {
            error("Failed to " + action + " config!", e);
        }
    }

    /**
     * Restores the options from the config file.
     *
     * @return if the version specified in the config is outdated and the config should be updated
     */
    public static boolean readConfig(String action) {
        Version version = null;

        try {
            List<String> lines = Files.readAllLines(CONFIG.toPath());

            for (String line : lines) {
                try {
                    if (!line.startsWith("#") && !line.trim().isEmpty()) {
                        String[] split = line.split(":", 2);
                        split[1] = split[1].trim();

                        switch (split[0].trim()) {
                            case "version" -> version = Version.parse(split[1]);
                            case "showToasts" -> showToasts = Boolean.parseBoolean(split[1]);
                            case "renderSavingScreen" -> renderSavingScreen = Boolean.parseBoolean(split[1]);
                            case "backgroundPriority" -> backgroundPriority = Math.max(0, Math.min(Thread.MAX_PRIORITY, Integer.parseInt(split[1])));
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (IOException e) {
            error("Failed to " + action + " config!", e);
        }
        return version == null || version.compareTo(FASTQUIT.getVersion()) < 0;
    }

    /**
     * Waits for all servers to finish saving, should be called when Minecraft is closed.
     * Catches everything to avoid any issues in the areas where it's called.
     */
    public static void exit() {
        try {
            wait(savingWorlds.keySet());
        } catch (Throwable throwable) {
            error("Something went horribly wrong when exiting FastQuit!", throwable);
            savingWorlds.forEach((server, info) -> {
                try {
                    server.getThread().join();
                } catch (Throwable throwable2) {
                    error("Failed to wait for \"" + server.getSaveProperties().getLevelName() + "\"", throwable2);
                }
            });
        }
    }

    /**
     * @see FastQuit#wait(Collection, CallbackInfo)
     */
    public static void wait(Collection<IntegratedServer> servers) {
        wait(servers, null);
    }

    /**
     * Waits for all the {@link IntegratedServer}'s in the given {@link Collection} to finish saving and in the meantime renders a {@link WaitingScreen}.
     * If a {@link CallbackInfo} is given, the waiting can be cancelled by the user.
     *
     * @throws IllegalStateException if called on one of the given {@link IntegratedServer}'s threads, would cause a deadlock otherwise
     */
    public static void wait(Collection<IntegratedServer> servers, @Nullable CallbackInfo cancellable) {
        if (servers == null || servers.isEmpty()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();

        if (!client.isOnThread()) {
            if (servers.stream().anyMatch(server -> Thread.currentThread() == server.getThread())) {
                throw new IllegalStateException("Tried to call FastQuit.wait(...) from one of the servers it's supposed to wait for.");
            }

            client.submit(() -> wait(servers)).join();
            return;
        }

        Screen oldScreen = client.currentScreen;

        Text stillSaving = TextHelper.translatable("screen.fastquit.waiting", String.join("\" & \"", servers.stream().map(server -> server.getSaveProperties().getLevelName()).toList()));
        log(stillSaving.getString());

        servers.forEach(server -> server.getThread().setPriority(Thread.NORM_PRIORITY));

        try {
            client.setScreen(new WaitingScreen(stillSaving, cancellable));

            while (servers.stream().anyMatch(server -> !server.isStopping())) {
                if (cancellable != null && cancellable.isCancelled()) {
                    if (backgroundPriority != 0) {
                        servers.forEach(server -> server.getThread().setPriority(backgroundPriority));
                    }
                    break;
                }
                ((MinecraftClientAccessor) client).callRender(false);
            }
        } finally {
            // compatibility with "WorldGen" mod
            if (oldScreen != null && oldScreen.getClass().getName().equals("caeruleusTait.WorldGen.gui.screens.WGConfigScreen")) {
                client.currentScreen = oldScreen;
            } else {
                client.setScreen(oldScreen);
            }
        }
    }

    /**
     * @return optionally returns the currently saving world matching the given path
     */
    public static Optional<IntegratedServer> getSavingWorld(Path path) {
        return savingWorlds.keySet().stream().filter(server -> ((SessionAccessor) ((MinecraftServerAccessor) server).getSession()).getDirectory().path().equals(path)).findFirst();
    }
}