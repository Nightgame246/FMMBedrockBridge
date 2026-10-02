package de.crazypandas.fmmbedrockbridge.bridge;

import com.github.retrooper.packetevents.protocol.component.ComponentType;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.PatchableComponentMap;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemModel;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 7.7 — applies {@link BedrockMenuIcons} to a PacketEvents item: same amount, same component
 * patches (name, lore, item_model, …), base item paper. Returns the same instance when nothing changes.
 */
final class MenuIconRebaser {

    private MenuIconRebaser() {}

    static ItemStack rebase(ItemStack item, List<String> prefixes, Set<String> excludes) {
        if (item == null || item.isEmpty()) return item;
        String model = item.getComponent(ComponentTypes.ITEM_MODEL)
                .map(ItemModel::getModelLocation)
                .map(Object::toString)
                .orElse(null);
        String base = item.getType().getName().toString();
        if (!BedrockMenuIcons.shouldRebase(base, model, prefixes, excludes)) return item;

        Map<ComponentType<?>, Optional<?>> patches = new HashMap<>(item.getComponents().getPatches());
        PatchableComponentMap components = new PatchableComponentMap(ItemTypes.PAPER.getComponents(), patches);
        return ItemStack.builder()
                .type(ItemTypes.PAPER)
                .amount(item.getAmount())
                .components(components)
                .build();
    }
}
