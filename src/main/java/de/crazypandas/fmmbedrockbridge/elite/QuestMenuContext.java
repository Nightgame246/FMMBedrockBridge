package de.crazypandas.fmmbedrockbridge.elite;

import java.util.List;

/**
 * Opaque carrier for the EliteMobs context recovered from a Bedrock quest chest:
 * the quests being offered and the originating NPC. Phase 7.3b.
 *
 * <p>The bridge never inspects these EM objects — it only passes them straight back
 * into EM's {@code QuestMenu.generateDialogMenu(...)}. Kept as raw types ({@code List<?>},
 * {@code Object}) so this codebase does not compile-depend on EM's {@code Quest}/
 * {@code NPCEntity} classes (they are resolved by reflection in {@link EliteMobsHook}).
 */
public record QuestMenuContext(List<?> quests, Object npcEntity) {}
