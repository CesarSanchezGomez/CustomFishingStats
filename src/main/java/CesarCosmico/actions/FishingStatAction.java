package CesarCosmico.actions;

import CesarCosmico.CustomFishingStats;
import net.momirealms.customfishing.api.mechanic.action.Action;
import net.momirealms.customfishing.api.mechanic.context.Context;
import net.momirealms.customfishing.api.mechanic.misc.value.MathValue;
import net.momirealms.customfishing.libraries.boostedyaml.block.implementation.Section;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Custom action para registrar estadísticas de pesca.
 * Responsabilidad única: procesar y ejecutar el tracking de estadísticas desde CustomFishing.
 *
 * Aplica:
 * - Single Responsibility: Solo maneja registro de stats desde CustomFishing
 * - Open/Closed: Extensible mediante nuevos tipos de tracking
 * - Dependency Inversion: Depende de CustomFishingStats abstracto
 */
public class FishingStatAction {
    private static final String ACTION_NAME = "fishing-stat";

    private final CustomFishingStats plugin;

    public FishingStatAction(CustomFishingStats plugin) {
        this.plugin = plugin;
    }

    public void register() {
        plugin.getActionManager().registerAction((args, chance) -> {
            if (!(args instanceof Section)) {
                logWarning("Invalid argument type for " + ACTION_NAME);
                return Action.empty();
            }

            Section section = (Section) args;
            List<FishingStatContext> contexts = parseContexts(section);

            if (contexts.isEmpty()) {
                return Action.empty();
            }

            boolean globalOnly = section.getBoolean("global_only", false);
            return createAction(contexts, globalOnly, chance);

        }, ACTION_NAME);
    }

    private List<FishingStatContext> parseContexts(Section section) {
        String type = section.getString("type");
        if (type == null || type.isEmpty()) {
            logWarning("requires 'type' parameter (competition, event, recycling, etc.)");
            return Collections.emptyList();
        }

        List<String> categories = extractCategories(section);
        if (categories.isEmpty()) {
            return Collections.emptyList();
        }

        int amount = section.getInt("amount", 1);
        String item = section.getString("item");

        return buildContexts(type, categories, amount, item);
    }

    private List<String> extractCategories(Section section) {
        boolean hasCategory = section.contains("category");
        boolean hasCategories = section.contains("categories");

        if (hasCategory && hasCategories) {
            logWarning("cannot specify both 'category' and 'categories'");
            return Collections.emptyList();
        }

        if (!hasCategory && !hasCategories) {
            logWarning("requires 'category' or 'categories' parameter");
            return Collections.emptyList();
        }

        if (hasCategories) {
            return section.getStringList("categories");
        }

        if (section.isList("category")) {
            return section.getStringList("category");
        }

        String category = section.getString("category");
        if (category == null || category.isEmpty()) {
            logWarning("invalid 'category' parameter");
            return Collections.emptyList();
        }

        return Collections.singletonList(category);
    }

    private List<FishingStatContext> buildContexts(String type, List<String> categories, int amount, String item) {
        List<FishingStatContext> contexts = new ArrayList<>();

        for (String category : categories) {
            FishingStatContext.Builder builder = FishingStatContext.builder()
                    .type(type)
                    .category(category)
                    .amount(amount);

            if (item != null && !item.isEmpty()) {
                builder.item(item);
            }

            contexts.add(builder.build());
        }

        return contexts;
    }

    /**
     * Crea la acción que se ejecutará cuando se cumplan las condiciones
     * OPTIMIZED: Usa trackStats que no persiste inmediatamente
     */
    private Action<Player> createAction(List<FishingStatContext> contexts, boolean globalOnly, MathValue<Player> chance) {
        return (Context<Player> ctxPlayer) -> {
            if (Math.random() > chance.evaluate(ctxPlayer)) {
                return;
            }

            Player player = globalOnly ? null : ctxPlayer.holder();

            for (FishingStatContext context : contexts) {
                plugin.trackStats(player, context);
            }
        };
    }

    private void logWarning(String message) {
        plugin.getLogger().warning(ACTION_NAME + " " + message);
    }
}