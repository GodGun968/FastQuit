package com.kingcontaria.fastquit;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashSet;
import java.util.Set;

@Config(name = "fastquit")
public class FastQuitConfig implements ConfigData {

    /**
     * Determines whether the "Saving world" screen gets rendered.
     */
    public boolean renderSavingScreen = false;

    /**
     * Determines whether a toast gets shown when a world finishes saving.
     */
    public boolean showToasts = true;

    /**
     * Determines whether the time it took to save the world gets displayed on toasts and the world list.
     */
    public ShowSavingTime showSavingTime = ShowSavingTime.TRUE;

    /**
     * Determines the Thread priority used for {@link IntegratedServer}'s saving in the background.
     * Value needs to be between 0 and 10, with Thread priority staying unchanged if the value is 0.
     */
    public int backgroundPriority = 2;

    /**
     * Determines whether multiple {@link IntegratedServer}'s can be running at the same time.
     * This is safe in vanilla minecraft but some mods can have issues with it.
     *
     * @apiNote Access through {@link FastQuitConfig#allowMultipleServers()} to avoid known mod conflicts!
     */
    private boolean allowMultipleServers = true;

    /**
     * This {@link Set} holds the names of all currently active mods that conflict with {@link FastQuitConfig#allowMultipleServers}.
     * @see FastQuitConfig#allowMultipleServers()
     */
    private static final Set<String> MODS_THAT_CONFLICT_WITH_MULTIPLE_SERVERS = new HashSet<>();

    static {
        // Put all conflicting Mod ID's in this set
        Set<String> incompatibleModIDs = Set.of("quilt_biome");

        for (String modID : incompatibleModIDs) {
            FabricLoader.getInstance().getModContainer(modID).ifPresent(modContainer -> MODS_THAT_CONFLICT_WITH_MULTIPLE_SERVERS.add(modContainer.getMetadata().getName()));
        }
    }

    /**
     * @return Returns {@code false} when Quilt Biome API is loaded, returns {@link FastQuitConfig#allowMultipleServers} otherwise.
     */
    public boolean allowMultipleServers() {
        if (!MODS_THAT_CONFLICT_WITH_MULTIPLE_SERVERS.isEmpty()) {
            return false;
        }
        return allowMultipleServers;
    }

    /**
     * @return Returns a config screen built with Cloth Config API
     */
    public Screen createConfigScreen(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(TextHelper.literal(FastQuit.FASTQUIT.getName()))
                .setSavingRunnable(() -> AutoConfig.getConfigHolder(FastQuitConfig.class).save());

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        // General Settings

        ConfigCategory generalCategory = builder.getOrCreateCategory(TextHelper.translatable("fastquit.config.general"));

        generalCategory.addEntry(entryBuilder.startBooleanToggle(TextHelper.translatable("fastquit.config.general.showToasts"), this.showToasts)
                .setTooltip(TextHelper.translatable("fastquit.config.general.showToasts.description"))
                .setDefaultValue(true)
                .setSaveConsumer(showToasts -> this.showToasts = showToasts)
                .build()
        );

        generalCategory.addEntry(entryBuilder.startBooleanToggle(TextHelper.translatable("fastquit.config.general.renderSavingScreen"), this.renderSavingScreen)
                .setTooltip(TextHelper.translatable("fastquit.config.general.renderSavingScreen.description"))
                .setDefaultValue(false)
                .setSaveConsumer(renderSavingScreen -> this.renderSavingScreen = renderSavingScreen)
                .build()
        );

        generalCategory.addEntry(entryBuilder.startEnumSelector(TextHelper.translatable("fastquit.config.general.showSavingTime"), FastQuitConfig.ShowSavingTime.class, this.showSavingTime)
                .setTooltip(TextHelper.translatable("fastquit.config.general.showSavingTime.description"))
                .setEnumNameProvider(showSavingTime -> {
                    if (showSavingTime == ShowSavingTime.TOAST_ONLY) {
                        return TextHelper.translatable("fastquit.config.general.showSavingTime.toastsOnly");
                    }
                    return Text.translatable("text.cloth-config.boolean.value." + (showSavingTime == ShowSavingTime.TRUE));
                })
                .setDefaultValue(ShowSavingTime.TRUE)
                .setSaveConsumer(showSavingTime -> this.showSavingTime = showSavingTime)
                .build()
        );

        // Performance Settings

        ConfigCategory performanceCategory = builder.getOrCreateCategory(TextHelper.translatable("fastquit.config.performance"));

        performanceCategory.addEntry(entryBuilder.startIntSlider(TextHelper.translatable("fastquit.config.performance.backgroundPriority"), this.backgroundPriority, 0, Thread.MAX_PRIORITY)
                .setTooltip(TextHelper.translatable("fastquit.config.performance.backgroundPriority.description"))
                .setTextGetter(backgroundPriority -> switch (backgroundPriority) {
                    case 0 -> TextHelper.OFF;
                    case 1, 2, 5, 10 -> TextHelper.translatable("fastquit.config.performance.backgroundPriority." + backgroundPriority);
                    default -> TextHelper.literal(backgroundPriority.toString());
                })
                .setDefaultValue(2)
                .setSaveConsumer(backgroundPriority -> this.backgroundPriority = backgroundPriority)
                .build()
        );

        // Mod Compatibility Settings

        ConfigCategory modCompatCategory = builder.getOrCreateCategory(TextHelper.translatable("fastquit.config.compat"));

        if (MODS_THAT_CONFLICT_WITH_MULTIPLE_SERVERS.isEmpty()) {
            modCompatCategory.addEntry(entryBuilder.startBooleanToggle(TextHelper.translatable("fastquit.config.compat.allowMultipleServers"), this.allowMultipleServers)
                    .setTooltip(TextHelper.translatable("fastquit.config.compat.allowMultipleServers.description"))
                    .setDefaultValue(true)
                    .setSaveConsumer(allowMultipleServers -> this.allowMultipleServers = allowMultipleServers)
                    .build()
            );
        } else {
            modCompatCategory.addEntry(entryBuilder.startEnumSelector(TextHelper.translatable("fastquit.config.compat.allowMultipleServers"), ModCompat.class, ModCompat.DISABLED)
                    .setTooltip(TextHelper.translatable("fastquit.config.compat.allowMultipleServers.description").append("\n\n").append(TextHelper.translatable("fastquit.config.compat.allowMultipleServers.disabledForCompat", String.join(", ", MODS_THAT_CONFLICT_WITH_MULTIPLE_SERVERS))))
                    .setEnumNameProvider(disabled -> TextHelper.translatable("addServer.resourcePack.disabled").styled(style -> style.withColor(Formatting.RED)))
                    .build()
            );
        }

        return builder.build();
    }

    public enum ShowSavingTime {
        FALSE,
        TOAST_ONLY,
        TRUE
    }
    
    private enum ModCompat {
        DISABLED
    }
}
