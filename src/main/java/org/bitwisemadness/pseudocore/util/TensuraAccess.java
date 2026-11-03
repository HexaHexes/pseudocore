package org.bitwisemadness.pseudocore.util;

import io.github.manasmods.manascore.storage.api.StorageHolder;
import io.github.manasmods.manascore.storage.api.StorageKey;
import io.github.manasmods.tensura.storage.ep.ExistenceStorage;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * The only class in this addon that imports Tensura or ManasCore types.
 *
 * <p>Never reference this class directly — always go through
 * {@link TensuraBridge}, which gates every call on ModList so this class is
 * only loaded when Tensura is actually installed. Loading it without Tensura
 * would throw {@link NoClassDefFoundError}.</p>
 */
final class TensuraAccess {

    /** ManasCore's per-entity storage root, written by its MixinEntity. */
    private static final String STORAGE_ROOT = "ManasCoreStorage";
    /**
     * Inside that root, storages live in a <b>list</b> keyed by this name — not
     * in a compound keyed by storage id, which is what an earlier version of
     * this class assumed and why EP never showed.
     */
    private static final String STORAGE_LIST = "manascore_registry_storage";
    /** Each list element names the storage it belongs to with this string. */
    private static final String STORAGE_ID = "manascore_registry_storage_id";

    private TensuraAccess() {
    }

    /**
     * Read ExistenceStorage off any LivingEntity via the ManasCore mixin. This
     * is the same access pattern used by tensura_minecolonies' working code.
     */
    static ExistenceStorage readExistence(LivingEntity entity) {
        if (entity instanceof StorageHolder holder) {
            return holder.manasCore$getStorage(ExistenceStorage.getKey());
        }
        return null;
    }

    static Optional<ResourceLocation> existenceStorageId() {
        return Optional.of(ExistenceStorage.getKey().id());
    }

    /**
     * EP is aura + magicule (see Tensura's own getEP). Read from NBT so the
     * tooltip can show it without a live entity.
     */
    static Optional<Double> readEpFromNbt(CompoundTag entityData) {
        CompoundTag exist = existenceCompound(entityData);
        if (exist == null) return Optional.empty();
        if (!exist.contains("aura") && !exist.contains("magicule")) return Optional.empty();
        double aura = exist.getDouble("aura");
        double magicule = exist.getDouble("magicule");
        return Optional.of(aura + magicule);
    }

    /**
     * Tensura writes the owner to both permanentOwner and temporaryOwner. We
     * read permanent first since it is the durable, naming-granted one.
     */
    static Optional<UUID> readOwnerFromNbt(CompoundTag entityData) {
        CompoundTag exist = existenceCompound(entityData);
        if (exist == null) return Optional.empty();
        if (exist.hasUUID("permanentOwner")) return Optional.of(exist.getUUID("permanentOwner"));
        if (exist.hasUUID("temporaryOwner")) return Optional.of(exist.getUUID("temporaryOwner"));
        return Optional.empty();
    }

    static boolean hasOwner(LivingEntity entity) {
        ExistenceStorage exist = readExistence(entity);
        if (exist == null) return false;
        return exist.getPermanentOwner() != null || exist.getTemporaryOwner() != null;
    }

    /**
     * Live EP - Tensura's own {@code getEP()}, aura plus magicule. The live
     * storage rather than NBT, since the caller has the entity in hand.
     */
    static Optional<Double> readEp(LivingEntity entity) {
        ExistenceStorage exist = readExistence(entity);
        if (exist == null) return Optional.empty();
        return Optional.of(exist.getEP());
    }

    /**
     * The player's {@code [AP (aura), MP (magicule)]} pools (#159), or null
     * when the entity has no storage holder - the caller treats that as "no
     * economy here" rather than a refusal.
     */
    static double[] readPools(LivingEntity entity) {
        ExistenceStorage exist = readExistence(entity);
        if (exist == null) return null;
        return new double[]{exist.getAura(), exist.getMagicule()};
    }

    /**
     * Overwrite both pools and mark the storage dirty, so ManasCore syncs
     * the change (#159's charge path).
     */
    static void writePools(LivingEntity entity, double aura, double magicule) {
        ExistenceStorage exist = readExistence(entity);
        if (exist == null) return;
        exist.setAura(aura);
        exist.setMagicule(magicule);
        exist.markDirty();
    }

    /**
     * Whether Tensura's naming system has given this mob a name - not a vanilla
     * custom name. {@code ExistenceStorage#getName} is what Tensura's own naming
     * flow writes.
     */
    static boolean isNamed(LivingEntity entity) {
        ExistenceStorage exist = readExistence(entity);
        if (exist == null) return false;
        String name = exist.getName();
        return name != null && !name.isBlank();
    }

    static Optional<UUID> readOwner(LivingEntity entity) {
        ExistenceStorage exist = readExistence(entity);
        if (exist == null) return Optional.empty();
        if (exist.getPermanentOwner() != null) return Optional.of(exist.getPermanentOwner());
        if (exist.getTemporaryOwner() != null) return Optional.of(exist.getTemporaryOwner());
        return Optional.empty();
    }

    static boolean transferOwnership(LivingEntity entity, UUID newOwner) {
        ExistenceStorage exist = readExistence(entity);
        if (exist == null) return false;
        exist.setPermanentOwner(newOwner);
        exist.setTemporaryOwner(newOwner);
        exist.markDirty();
        return true;
    }

    /**
     * Dig the ExistenceStorage compound out of the captured entity NBT.
     *
     * <p>ManasCore nests storages two levels deep and holds them in a
     * <b>list</b>, not a compound keyed by storage id. {@code MixinEntity}
     * writes the {@code ManasCoreStorage} compound; {@code CombinedStorage#toNBT}
     * puts a {@code manascore_registry_storage} ListTag inside it; each element
     * then carries a {@code manascore_registry_storage_id} string alongside the
     * storage's own fields. {@code ExistenceStorage#save} writes aura, magicule
     * and both owner UUIDs directly into that element, so they are read straight
     * off the matched entry rather than from anything nested further.</p>
     */
    private static CompoundTag existenceCompound(CompoundTag entityData) {
        if (!entityData.contains(STORAGE_ROOT, Tag.TAG_COMPOUND)) return null;
        CompoundTag all = entityData.getCompound(STORAGE_ROOT);
        if (!all.contains(STORAGE_LIST, Tag.TAG_LIST)) return null;

        // Resolved from the API rather than hardcoded, so a Tensura key rename
        // cannot silently break us. Assigned from a registration event, so it
        // can still be null this early - guard rather than lean on the bridge.
        StorageKey<ExistenceStorage> key = ExistenceStorage.getKey();
        if (key == null) return null;
        String wanted = key.id().toString();

        ListTag list = all.getList(STORAGE_LIST, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (wanted.equals(entry.getString(STORAGE_ID))) return entry;
        }
        return null;
    }
}
